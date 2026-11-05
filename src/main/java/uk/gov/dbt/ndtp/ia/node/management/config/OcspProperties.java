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
 * @param signer            location of the delegated OCSP signer key and certificate
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
     * Vault KV v2 location of the OCSP signer.
     *
     * @param kvMount         KV v2 mount
     * @param kvPath          secret path holding {@code private_key} (PKCS#8 PEM) and {@code certificate} (PEM)
     * @param refreshInterval how often the signer is reloaded from Vault
     */
    public record Signer(
            @DefaultValue("secret") String kvMount,
            @DefaultValue("management-node/ocsp-signer") String kvPath,
            @DefaultValue("PT5M") Duration refreshInterval) {}
}
