/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the OCSP responder ({@code application.ocsp.*}).
 *
 * @param validity          time between {@code thisUpdate} and {@code nextUpdate}; must be positive and at most
 *                          {@link #MAX_VALIDITY}
 * @param maxRequestEntries maximum number of certificate IDs answered in a single request
 * @param maxRequestBytes   maximum accepted size of a DER-encoded OCSP request
 * @param statusBackend     certificate status backend ({@code vault})
 * @param signer            OCSP signer certificate settings
 */
@ConfigurationProperties("application.ocsp")
public record OcspProperties(
        @DefaultValue("PT5M") Duration validity,
        @DefaultValue("10") int maxRequestEntries,
        @DefaultValue("16384") int maxRequestBytes,
        @DefaultValue("vault") String statusBackend,
        @DefaultValue Signer signer) {

    /** Upper bound on response validity, which bounds how long a revoked certificate can stay in use. */
    public static final Duration MAX_VALIDITY = Duration.ofMinutes(10);

    public OcspProperties {
        if (validity == null || validity.isNegative() || validity.isZero() || validity.compareTo(MAX_VALIDITY) > 0) {
            throw new IllegalArgumentException("application.ocsp.validity must be greater than 0 and at most "
                    + MAX_VALIDITY + ", was " + validity);
        }
        if (maxRequestEntries < 1) {
            throw new IllegalArgumentException("application.ocsp.max-request-entries must be at least 1");
        }
        if (maxRequestBytes < 1) {
            throw new IllegalArgumentException("application.ocsp.max-request-bytes must be at least 1");
        }
    }

    /**
     * OCSP signer issued by the Management Node through the default Vault PKI role.
     *
     * @param commonName      common name of the signer certificate
     * @param ttl             lifetime requested for the signer certificate; at least four times
     *                        {@code refreshInterval}, so the renewal window (the last 30% of the lifetime) is
     *                        longer than the time between checks
     * @param refreshInterval how often the service checks whether the signer is due for renewal
     */
    public record Signer(
            @DefaultValue("ocsp-signer.management-node") String commonName,
            @DefaultValue("PT24H") Duration ttl,
            @DefaultValue("PT5M") Duration refreshInterval) {

        public Signer {
            if (commonName == null || commonName.isBlank()) {
                throw new IllegalArgumentException("application.ocsp.signer.common-name must not be blank");
            }
            if (refreshInterval == null || refreshInterval.isNegative() || refreshInterval.isZero()) {
                throw new IllegalArgumentException("application.ocsp.signer.refresh-interval must be greater than 0");
            }
            if (ttl == null || ttl.compareTo(refreshInterval.multipliedBy(4)) < 0) {
                throw new IllegalArgumentException("application.ocsp.signer.ttl must be at least four times"
                        + " application.ocsp.signer.refresh-interval, was " + ttl);
            }
        }
    }
}
