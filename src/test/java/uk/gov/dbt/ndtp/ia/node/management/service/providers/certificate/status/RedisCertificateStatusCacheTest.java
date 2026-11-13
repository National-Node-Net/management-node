/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.CA;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import uk.gov.dbt.ndtp.ia.node.management.config.CertificateStatusCacheConfig;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CertificateStatusDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.RevocationReason;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.CertificateStatusProvider;

/**
 * The status cache shared by several instances, {@code spring.cache.type=redis}, against a real Redis in a
 * container. Skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisCertificateStatusCacheTest extends AbstractCertificateStatusCacheTest {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Override
    protected String[] cacheProperties() {
        return new String[] {
            "spring.cache.type=redis",
            "spring.data.redis.host=" + REDIS.getHost(),
            "spring.data.redis.port=" + REDIS.getMappedPort(6379)
        };
    }

    private String redisKey() {
        return CertificateStatusCacheConfig.REDIS_KEY_PREFIX + ISSUER.keyHash() + ":" + serial.toString(16);
    }

    @Test
    void cacheManager_shouldBeRedis() {
        contextRunner("5m")
                .run(context -> assertThat(context.getBean(CacheManager.class)).isInstanceOf(RedisCacheManager.class));
    }

    @Test
    void entry_shouldBeJsonUnderThePrefixedKeyAndExpireWithTheTtl() {
        Instant revokedAt = Instant.parse("2026-10-12T13:15:02Z");
        when(statusProvider.getStatus(serial, CA))
                .thenReturn(CertificateStatusDTO.revoked(revokedAt, RevocationReason.KEY_COMPROMISE));

        contextRunner("2m").run(context -> {
            context.getBean(CertificateStatusService.class).getStatus(ISSUER, serial);

            StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
            String value = redis.opsForValue().get(redisKey());
            assertNotNull(value, "the entry is stored under dsi:cert-status:<issuerKeyHash>:<serial>");
            JsonNode json = new ObjectMapper().readTree(value);
            assertEquals("REVOKED", json.get("status").asText());
            assertEquals("2026-10-12T13:15:02Z", json.get("revokedAt").asText());
            assertEquals("KEY_COMPROMISE", json.get("revocationReason").asText());
            assertThat(json.get("checkedAt").asText()).isNotBlank();
            assertThat(json.has("@class"))
                    .as("no type information in the value")
                    .isFalse();
            assertThat(redis.getExpire(redisKey(), TimeUnit.SECONDS)).isBetween(100L, 120L);
        });
    }

    /** Acceptance criterion: a revocation through instance A is enforced by instance B on its next request. */
    @Test
    void evict_onOneInstance_shouldReachAnother() {
        CertificateStatusProvider providerOfB = mock(CertificateStatusProvider.class);
        when(statusProvider.getStatus(serial, CA)).thenReturn(CertificateStatusDTO.good());
        when(providerOfB.getStatus(serial, CA)).thenReturn(CertificateStatusDTO.revoked(Instant.now()));

        contextRunner("5m").run(instanceA -> contextRunner("5m", providerOfB).run(instanceB -> {
            CertificateStatusService serviceA = instanceA.getBean(CertificateStatusService.class);
            CertificateStatusService serviceB = instanceB.getBean(CertificateStatusService.class);

            serviceA.getStatus(ISSUER, serial);
            assertEquals(
                    CertificateStatusDTO.Status.GOOD,
                    serviceB.getStatus(ISSUER, serial).getStatus(),
                    "instance B reads the entry instance A wrote");
            verify(providerOfB, never()).getStatus(serial, CA);

            serviceA.evict(ISSUER, serial);

            assertEquals(
                    CertificateStatusDTO.Status.REVOKED,
                    serviceB.getStatus(ISSUER, serial).getStatus(),
                    "after the eviction on A, instance B looks the status up again");
        }));
    }

    /** An unreachable Redis must slow requests down, not fail them: the status comes from its source. */
    @Test
    void unreachableRedis_shouldFallBackToTheBackend() {
        when(statusProvider.getStatus(serial, CA)).thenReturn(CertificateStatusDTO.good());

        contextRunner("5m")
                .withPropertyValues("spring.data.redis.port=1", "spring.data.redis.timeout=500ms")
                .run(context -> {
                    CertificateStatusService service = context.getBean(CertificateStatusService.class);

                    assertEquals(
                            CertificateStatusDTO.Status.GOOD,
                            service.getStatus(ISSUER, serial).getStatus());
                    service.evict(ISSUER, serial);
                });
    }
}
