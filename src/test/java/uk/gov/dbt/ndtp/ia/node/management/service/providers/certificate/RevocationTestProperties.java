/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate;

import java.time.Duration;
import java.util.List;
import uk.gov.dbt.ndtp.ia.node.management.config.RevocationProperties;

/** Revocation properties for tests: the default limits, with retries that do not wait. */
public final class RevocationTestProperties {

    /** Attempts in total that {@link #fast()} makes against Vault. */
    public static final int MAX_ATTEMPTS = 3;

    private static final List<String> EXCLUDED_PATHS = List.of("/api/v1/certificate/ocsp", "/actuator/health");

    private RevocationTestProperties() {}

    public static RevocationProperties fast() {
        return withRateLimit(10, Duration.ofMinutes(1));
    }

    public static RevocationProperties withRateLimit(int maxRequests, Duration window) {
        return new RevocationProperties(
                new RevocationProperties.RateLimit(maxRequests, window),
                vaultRetry(),
                new RevocationProperties.Enforcement(true, true, EXCLUDED_PATHS, List.of()),
                new RevocationProperties.Cache(Duration.ofMinutes(5)));
    }

    /** The default excluded paths, with the given failure mode and exempt issuers. */
    public static RevocationProperties withEnforcement(boolean failClosed, List<String> exemptIssuers) {
        return new RevocationProperties(
                new RevocationProperties.RateLimit(10, Duration.ofMinutes(1)),
                vaultRetry(),
                new RevocationProperties.Enforcement(true, failClosed, EXCLUDED_PATHS, exemptIssuers),
                new RevocationProperties.Cache(Duration.ofMinutes(5)));
    }

    private static RevocationProperties.VaultRetry vaultRetry() {
        return new RevocationProperties.VaultRetry(MAX_ATTEMPTS, Duration.ofMillis(1), Duration.ofMillis(2));
    }
}
