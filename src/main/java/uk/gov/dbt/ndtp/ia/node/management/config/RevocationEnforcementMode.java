/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.server.Ssl;
import org.springframework.core.env.Environment;

/**
 * Whether the revocation check on requests runs, and if not, why. Enforcement is active only when the feature flag
 * {@value RevocationEnforcementConfig#ENABLED_PROPERTY} is on <em>and</em> clients authenticate with mTLS at Tomcat:
 * {@code server.ssl.enabled} is true and {@code server.ssl.client-auth} is {@code need} or {@code want}. Without mTLS
 * no client presents a certificate, so there is nothing to check.
 *
 * <p>The one place this is decided: the {@link MtlsEnabledCondition} on the filter bean, the startup log line and
 * {@code /actuator/info} all read it, so they cannot disagree.
 */
public enum RevocationEnforcementMode {
    MTLS_REQUIRED(
            true,
            "mTLS required (server.ssl.client-auth=need): every request presents a client certificate and is checked"),
    MTLS_OPTIONAL(
            true,
            "mTLS optional (server.ssl.client-auth=want): requests that present a client certificate are checked"),
    DISABLED_BY_FLAG(false, "feature flag off (" + RevocationEnforcementConfig.ENABLED_PROPERTY + "=false)"),
    SSL_DISABLED(false, "SSL disabled (server.ssl.enabled=false): plain HTTP, for local development only"),
    MTLS_DISABLED(
            false,
            "mTLS disabled (server.ssl.client-auth is none or not set): HTTPS without client certificates, for local"
                    + " development only");

    static final String SSL_ENABLED_PROPERTY = "server.ssl.enabled";
    static final String CLIENT_AUTH_PROPERTY = "server.ssl.client-auth";

    private final boolean active;
    private final String reason;

    RevocationEnforcementMode(boolean active, String reason) {
        this.active = active;
        this.reason = reason;
    }

    /** True when the revocation filter is registered and checks requests. */
    public boolean isActive() {
        return active;
    }

    /** What the mode means, for the startup log and {@code /actuator/info}. */
    public String reason() {
        return reason;
    }

    /**
     * The mode for this environment. When more than one reason applies, the first of feature flag, SSL and mTLS is
     * given.
     */
    public static RevocationEnforcementMode resolve(Environment environment) {
        return flagEnabled(environment) ? resolveTransport(environment) : DISABLED_BY_FLAG;
    }

    /** The mode from {@code server.ssl.*} alone, ignoring the feature flag. */
    static RevocationEnforcementMode resolveTransport(Environment environment) {
        Binder binder = Binder.get(environment);
        if (!binder.bind(SSL_ENABLED_PROPERTY, Boolean.class).orElse(true)) {
            return SSL_DISABLED;
        }
        return switch (binder.bind(CLIENT_AUTH_PROPERTY, Ssl.ClientAuth.class).orElse(Ssl.ClientAuth.NONE)) {
            case NEED -> MTLS_REQUIRED;
            case WANT -> MTLS_OPTIONAL;
            case NONE -> MTLS_DISABLED;
        };
    }

    /** Matches {@code @ConditionalOnProperty(havingValue = "true", matchIfMissing = true)} on the filter bean. */
    private static boolean flagEnabled(Environment environment) {
        String value = environment.getProperty(RevocationEnforcementConfig.ENABLED_PROPERTY);
        return value == null || "true".equalsIgnoreCase(value.trim());
    }
}
