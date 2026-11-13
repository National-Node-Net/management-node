/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.CA;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import uk.gov.dbt.ndtp.ia.node.management.config.CertificateStatusCacheConfig;
import uk.gov.dbt.ndtp.ia.node.management.config.RevocationConfig;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateStatusUnavailableException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CertificateStatusDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.RevocationReason;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.CertificateStatusProvider;

/**
 * The caching acceptance criteria of the certificate status service, run against the cache manager Spring Boot
 * creates from the application's own cache configuration. Subclasses choose the provider with
 * {@code spring.cache.type}, so the same behaviour is proven for Caffeine and for Redis.
 */
abstract class AbstractCertificateStatusCacheTest {

    protected static final CertificateIssuer ISSUER = CertificateIssuer.of(CA);
    private static final SecureRandom RANDOM = new SecureRandom();

    protected CertificateStatusProvider statusProvider;

    /** A serial of its own for every test, so tests sharing one Redis do not see each other's entries. */
    protected BigInteger serial;

    @BeforeEach
    void newProviderAndSerial() {
        statusProvider = mock(CertificateStatusProvider.class);
        serial = new BigInteger(120, RANDOM);
    }

    /** The properties that select and reach the cache provider under test. */
    protected abstract String[] cacheProperties();

    /** An application context holding the status service, its cache and nothing else. */
    protected ApplicationContextRunner contextRunner(String ttl) {
        return contextRunner(ttl, statusProvider);
    }

    protected ApplicationContextRunner contextRunner(String ttl, CertificateStatusProvider provider) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CacheAutoConfiguration.class))
                .withUserConfiguration(
                        RevocationConfig.class, CertificateStatusCacheConfig.class, CertificateStatusServiceImpl.class)
                .withBean(CertificateStatusProvider.class, () -> provider)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(cacheProperties())
                .withPropertyValues(
                        "spring.cache.cache-names=" + CertificateStatusCacheConfig.CACHE_NAME,
                        "application.revocation.cache.ttl=" + ttl);
    }

    @Test
    void cacheManager_shouldHoldTheCertificateStatusCache() {
        contextRunner("5m").run(context -> {
            assertThat(context).hasNotFailed();
            assertNotNull(context.getBean(CacheManager.class).getCache(CertificateStatusCacheConfig.CACHE_NAME));
        });
    }

    @Test
    void cachedStatus_shouldBeReusedWithoutAskingTheBackend() {
        when(statusProvider.getStatus(serial, CA)).thenReturn(CertificateStatusDTO.good());

        contextRunner("5m").run(context -> {
            CertificateStatusService service = context.getBean(CertificateStatusService.class);

            CertificateStatusDTO first = service.getStatus(ISSUER, serial);
            CertificateStatusDTO second = service.getStatus(ISSUER, serial);

            assertEquals(CertificateStatusDTO.Status.GOOD, second.getStatus());
            assertEquals(first.getCheckedAt(), second.getCheckedAt(), "the second answer is the cached one");
            verify(statusProvider, times(1)).getStatus(serial, CA);
        });
    }

    @Test
    void revokedStatus_shouldBeCachedWithItsTimeAndReason() {
        Instant revokedAt = Instant.parse("2026-10-12T13:15:02Z");
        when(statusProvider.getStatus(serial, CA))
                .thenReturn(CertificateStatusDTO.revoked(revokedAt, RevocationReason.KEY_COMPROMISE));

        contextRunner("5m").run(context -> {
            CertificateStatusService service = context.getBean(CertificateStatusService.class);
            service.getStatus(ISSUER, serial);

            CertificateStatusDTO cached = service.getStatus(ISSUER, serial);

            assertEquals(CertificateStatusDTO.Status.REVOKED, cached.getStatus());
            assertEquals(revokedAt, cached.getRevokedAt());
            assertEquals(RevocationReason.KEY_COMPROMISE, cached.getRevocationReason());
            assertNotNull(cached.getCheckedAt());
            verify(statusProvider, times(1)).getStatus(serial, CA);
        });
    }

    @Test
    void unknownStatus_shouldBeCached() {
        when(statusProvider.getStatus(serial, CA)).thenReturn(CertificateStatusDTO.unknown());

        contextRunner("5m").run(context -> {
            CertificateStatusService service = context.getBean(CertificateStatusService.class);
            service.getStatus(ISSUER, serial);

            assertEquals(
                    CertificateStatusDTO.Status.UNKNOWN,
                    service.getStatus(ISSUER, serial).getStatus());
            verify(statusProvider, times(1)).getStatus(serial, CA);
        });
    }

    @Test
    void differentSerials_shouldHaveEntriesOfTheirOwn() {
        BigInteger other = serial.add(BigInteger.ONE);
        when(statusProvider.getStatus(serial, CA)).thenReturn(CertificateStatusDTO.good());
        when(statusProvider.getStatus(other, CA)).thenReturn(CertificateStatusDTO.revoked(Instant.now()));

        contextRunner("5m").run(context -> {
            CertificateStatusService service = context.getBean(CertificateStatusService.class);

            assertEquals(
                    CertificateStatusDTO.Status.GOOD,
                    service.getStatus(ISSUER, serial).getStatus());
            assertEquals(
                    CertificateStatusDTO.Status.REVOKED,
                    service.getStatus(ISSUER, other).getStatus());
        });
    }

    @Test
    void expiredEntry_shouldBeLookedUpAgain() {
        when(statusProvider.getStatus(serial, CA))
                .thenReturn(CertificateStatusDTO.good())
                .thenReturn(CertificateStatusDTO.revoked(Instant.now()));

        contextRunner("1s").run(context -> {
            CertificateStatusService service = context.getBean(CertificateStatusService.class);
            assertEquals(
                    CertificateStatusDTO.Status.GOOD,
                    service.getStatus(ISSUER, serial).getStatus());

            Thread.sleep(1500);

            assertEquals(
                    CertificateStatusDTO.Status.REVOKED,
                    service.getStatus(ISSUER, serial).getStatus(),
                    "a revocation made directly in the certificate authority shows once the entry expires");
            verify(statusProvider, times(2)).getStatus(serial, CA);
        });
    }

    @Test
    void evict_shouldMakeTheNextRequestLookTheStatusUpAgain() {
        when(statusProvider.getStatus(serial, CA))
                .thenReturn(CertificateStatusDTO.good())
                .thenReturn(CertificateStatusDTO.revoked(Instant.now()));

        contextRunner("5m").run(context -> {
            CertificateStatusService service = context.getBean(CertificateStatusService.class);
            service.getStatus(ISSUER, serial);

            service.evict(ISSUER, serial);

            assertEquals(
                    CertificateStatusDTO.Status.REVOKED,
                    service.getStatus(ISSUER, serial).getStatus());
        });
    }

    @Test
    void backendFailure_shouldNotBeCached() {
        when(statusProvider.getStatus(serial, CA))
                .thenThrow(new CertificateStatusUnavailableException("Vault is down", null))
                .thenReturn(CertificateStatusDTO.good());

        contextRunner("5m").run(context -> {
            CertificateStatusService service = context.getBean(CertificateStatusService.class);

            assertThrows(CertificateStatusUnavailableException.class, () -> service.getStatus(ISSUER, serial));
            assertEquals(
                    CertificateStatusDTO.Status.GOOD,
                    service.getStatus(ISSUER, serial).getStatus(),
                    "the failure left nothing in the cache, so the backend is asked again");
            verify(statusProvider, times(2)).getStatus(serial, CA);
        });
    }

    @Test
    void ttlAboveTenMinutes_shouldFailStartupNamingTheProperty() {
        contextRunner("11m").run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("application.revocation.cache.ttl")
                .hasMessageContaining("PT10M"));
    }
}
