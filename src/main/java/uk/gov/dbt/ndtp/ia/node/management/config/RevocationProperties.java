/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import java.time.Duration;
import java.util.List;
import javax.security.auth.x500.X500Principal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of certificate revocation ({@code application.revocation.*}): the revocation API, the check of
 * client certificates on every request, and the certificate status cache.
 *
 * @param rateLimit   how many revocation requests one administrator may send
 * @param vaultRetry  how a revocation that Vault could not take is retried
 * @param enforcement the revocation check on requests that present a client certificate
 * @param cache       the certificate status cache
 */
@ConfigurationProperties("application.revocation")
public record RevocationProperties(
        @DefaultValue RateLimit rateLimit,
        @DefaultValue VaultRetry vaultRetry,
        @DefaultValue Enforcement enforcement,
        @DefaultValue Cache cache) {

    /**
     * The revocation check on requests that present a client certificate.
     *
     * @param enabled       whether requests are checked at all; when false the filter is not registered
     * @param failClosed    whether a request is refused when the status cannot be determined; false lets it through
     * @param excludedPaths path patterns, within the application, that are never checked
     * @param exemptIssuers issuer distinguished names outside the federation CA whose certificates are let through
     *                      without a check, for example a development root CA
     */
    public record Enforcement(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("true") boolean failClosed,
            @DefaultValue({"/api/v1/certificate/ocsp", "/actuator/health"}) List<String> excludedPaths,
            @DefaultValue List<String> exemptIssuers) {

        public Enforcement {
            excludedPaths = excludedPaths == null ? List.of() : List.copyOf(excludedPaths);
            exemptIssuers = exemptIssuers == null ? List.of() : List.copyOf(exemptIssuers);
            for (String issuer : exemptIssuers) {
                try {
                    new X500Principal(issuer);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "application.revocation.enforcement.exempt-issuers holds an invalid distinguished name: "
                                    + issuer,
                            e);
                }
            }
        }
    }

    /**
     * The certificate status cache.
     *
     * @param ttl how long a status is reused before the certificate authority is asked again; positive and at most
     *            {@link #MAX_TTL}
     */
    public record Cache(@DefaultValue("PT5M") Duration ttl) {

        /**
         * Upper bound on the time a status is reused, which bounds how long a certificate revoked directly in the
         * certificate authority stays accepted.
         */
        public static final Duration MAX_TTL = Duration.ofMinutes(10);

        public Cache {
            if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_TTL) > 0) {
                throw new IllegalArgumentException(
                        "application.revocation.cache.ttl must be greater than 0 and at most " + MAX_TTL + ", was "
                                + ttl);
            }
        }
    }

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
