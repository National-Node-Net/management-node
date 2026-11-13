/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the certificate revocation API ({@code application.revocation.*}).
 *
 * @param rateLimit  how many revocation requests one administrator may send
 * @param vaultRetry how a revocation that Vault could not take is retried
 */
@ConfigurationProperties("application.revocation")
public record RevocationProperties(@DefaultValue RateLimit rateLimit, @DefaultValue VaultRetry vaultRetry) {

    /**
     * Revocation requests allowed per administrator ({@code sub} claim) and window, counted per Management Node
     * instance. Bounds the damage a compromised administrator account can do.
     *
     * @param maxRequests requests allowed in one window
     * @param window      length of the window
     */
    public record RateLimit(@DefaultValue("10") int maxRequests, @DefaultValue("PT1M") Duration window) {

        public RateLimit {
            if (maxRequests < 1) {
                throw new IllegalArgumentException("application.revocation.rate-limit.max-requests must be at least 1");
            }
            if (window == null || window.isNegative() || window.isZero()) {
                throw new IllegalArgumentException("application.revocation.rate-limit.window must be greater than 0");
            }
        }
    }

    /**
     * Retries of a revocation that failed because Vault could not be reached or answered with a server error.
     * Refusals, such as a permission error, are never retried.
     *
     * @param maxAttempts    attempts in total, the first one included
     * @param initialBackoff wait before the first retry; doubled before each further retry
     * @param maxBackoff     longest wait between two attempts
     */
    public record VaultRetry(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("PT0.2S") Duration initialBackoff,
            @DefaultValue("PT2S") Duration maxBackoff) {

        public VaultRetry {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException(
                        "application.revocation.vault-retry.max-attempts must be at least 1");
            }
            if (initialBackoff == null || initialBackoff.toMillis() < 1) {
                throw new IllegalArgumentException(
                        "application.revocation.vault-retry.initial-backoff must be at least 1 ms");
            }
            if (maxBackoff == null || maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException(
                        "application.revocation.vault-retry.max-backoff must not be shorter than initial-backoff");
            }
        }
    }
}
