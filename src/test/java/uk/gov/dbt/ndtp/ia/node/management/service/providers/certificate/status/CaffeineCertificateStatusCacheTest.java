/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.CA;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CertificateStatusDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.CertificateStatusProvider;

/**
 * The status cache in the memory of one instance, {@code spring.cache.type=caffeine}. Redis is not configured, and
 * nothing of it is set up: no connection factory, no TLS context, no health indicator.
 */
class CaffeineCertificateStatusCacheTest extends AbstractCertificateStatusCacheTest {

    @Override
    protected String[] cacheProperties() {
        return new String[] {"spring.cache.type=caffeine", "spring.data.redis.ssl.enabled=true"};
    }

    @Test
    void cacheManager_shouldBeCaffeineAndStartWithoutRedis() {
        contextRunner("5m").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CacheManager.class)).isInstanceOf(CaffeineCacheManager.class);
            assertThat(context).doesNotHaveBean(RedisConnectionFactory.class);
        });
    }

    /** Why several instances need Redis: an eviction on one instance does not reach the memory of another. */
    @Test
    void evict_onOneInstance_shouldNotReachAnother() {
        CertificateStatusProvider providerOfB = mock(CertificateStatusProvider.class);
        when(statusProvider.getStatus(serial, CA)).thenReturn(CertificateStatusDTO.good());
        when(providerOfB.getStatus(serial, CA))
                .thenReturn(CertificateStatusDTO.good())
                .thenReturn(CertificateStatusDTO.revoked(Instant.now()));

        contextRunner("5m").run(instanceA -> contextRunner("5m", providerOfB).run(instanceB -> {
            CertificateStatusService serviceA = instanceA.getBean(CertificateStatusService.class);
            CertificateStatusService serviceB = instanceB.getBean(CertificateStatusService.class);
            serviceA.getStatus(ISSUER, serial);
            serviceB.getStatus(ISSUER, serial);

            serviceA.evict(ISSUER, serial);

            assertEquals(
                    CertificateStatusDTO.Status.GOOD,
                    serviceB.getStatus(ISSUER, serial).getStatus(),
                    "instance B keeps its own entry until it expires");
        }));
    }
}
