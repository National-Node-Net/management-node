/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigInteger;
import java.time.Clock;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.config.CertificateStatusCacheConfig;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateStatusUnavailableException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CertificateStatusDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.CertificateStatusProvider;

/**
 * Implementation of {@link CertificateStatusService}: asks the {@link CertificateStatusProvider} and lets Spring's
 * cache abstraction keep the answer under {@code <issuerKeyHash>:<serial>}. An exception leaves nothing in the
 * cache, which is Spring's default, so an unreachable backend is asked again on the next request.
 *
 * <p>The caching is applied by the proxy Spring puts around this bean; an instance created by hand does not cache.
 */
@Service
@Slf4j
public class CertificateStatusServiceImpl implements CertificateStatusService {

    /** {@code <issuerKeyHash>:<serial>}, the serial in lowercase hex without separators or leading zeros. */
    static final String CACHE_KEY = "#root.args[0].keyHash() + ':' + #root.args[1].toString(16)";

    private static final String LOOKUPS = "revocation.status.lookups";

    private final CertificateStatusProvider statusProvider;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Autowired
    public CertificateStatusServiceImpl(CertificateStatusProvider statusProvider, MeterRegistry meterRegistry) {
        this(statusProvider, meterRegistry, Clock.systemUTC());
    }

    public CertificateStatusServiceImpl(
            CertificateStatusProvider statusProvider, MeterRegistry meterRegistry, Clock clock) {
        this.statusProvider = statusProvider;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    @Cacheable(cacheNames = CertificateStatusCacheConfig.CACHE_NAME, key = CACHE_KEY)
    public CertificateStatusDTO getStatus(CertificateIssuer issuer, BigInteger serial) {
        CertificateStatusDTO status;
        try {
            status = statusProvider.getStatus(serial, issuer.certificate());
        } catch (CertificateStatusUnavailableException e) {
            count("unavailable");
            throw e;
        }
        count(status.getStatus().name().toLowerCase(Locale.ROOT));
        return CertificateStatusDTO.builder()
                .status(status.getStatus())
                .revokedAt(status.getRevokedAt())
                .revocationReason(status.getRevocationReason())
                .checkedAt(clock.instant())
                .build();
    }

    @Override
    @CacheEvict(cacheNames = CertificateStatusCacheConfig.CACHE_NAME, key = CACHE_KEY)
    public void evict(CertificateIssuer issuer, BigInteger serial) {
        log.debug("Evicting cached status of serial {} issued by {}", serial.toString(16), issuer.keyHash());
    }

    private void count(String result) {
        meterRegistry.counter(LOOKUPS, "result", result).increment();
    }
}
