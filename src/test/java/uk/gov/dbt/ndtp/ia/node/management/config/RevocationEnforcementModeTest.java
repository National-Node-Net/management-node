/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

/** The behaviour matrix of the ticket: SSL, client-auth and the feature flag. */
class RevocationEnforcementModeTest {

    private static final String UNSET = "-";

    @ParameterizedTest(name = "ssl={0} client-auth={1} flag={2} -> {3}")
    @CsvSource({
        "true, need, true,  MTLS_REQUIRED",
        "true, want, true,  MTLS_OPTIONAL",
        "true, none, true,  MTLS_DISABLED",
        "true, -,    true,  MTLS_DISABLED",
        "false, need, true, SSL_DISABLED",
        "false, -,   true,  SSL_DISABLED",
        "true, need, false, DISABLED_BY_FLAG",
        "true, want, false, DISABLED_BY_FLAG",
        "-,    need, -,     MTLS_REQUIRED",
        "-,    -,    -,     MTLS_DISABLED",
        "true, NEED, TRUE,  MTLS_REQUIRED",
        "true, Want, true,  MTLS_OPTIONAL"
    })
    void resolve_shouldFollowTheBehaviourMatrix(
            String sslEnabled, String clientAuth, String flag, RevocationEnforcementMode expected) {
        RevocationEnforcementMode mode = RevocationEnforcementMode.resolve(environment(sslEnabled, clientAuth, flag));

        assertThat(mode).isEqualTo(expected);
        assertThat(mode.isActive())
                .isEqualTo(expected == RevocationEnforcementMode.MTLS_REQUIRED
                        || expected == RevocationEnforcementMode.MTLS_OPTIONAL);
    }

    @Test
    void flagOffAndSslOff_shouldNameTheFlagAsTheReason() {
        assertThat(RevocationEnforcementMode.resolve(environment("false", UNSET, "false")))
                .isEqualTo(RevocationEnforcementMode.DISABLED_BY_FLAG);
    }

    /** Like @ConditionalOnProperty(havingValue = "true"): any other value switches the filter off. */
    @Test
    void flagWithAnUnexpectedValue_shouldCountAsOff() {
        assertThat(RevocationEnforcementMode.resolve(environment("true", "need", "yes")))
                .isEqualTo(RevocationEnforcementMode.DISABLED_BY_FLAG);
    }

    @Test
    void resolveTransport_shouldIgnoreTheFlag() {
        assertThat(RevocationEnforcementMode.resolveTransport(environment("true", "want", "false")))
                .isEqualTo(RevocationEnforcementMode.MTLS_OPTIONAL);
    }

    @Test
    void everyMode_shouldGiveAReason() {
        for (RevocationEnforcementMode mode : RevocationEnforcementMode.values()) {
            assertThat(mode.reason()).isNotBlank();
        }
    }

    private static MockEnvironment environment(String sslEnabled, String clientAuth, String flag) {
        MockEnvironment environment = new MockEnvironment();
        set(environment, RevocationEnforcementMode.SSL_ENABLED_PROPERTY, sslEnabled);
        set(environment, RevocationEnforcementMode.CLIENT_AUTH_PROPERTY, clientAuth);
        set(environment, RevocationEnforcementConfig.ENABLED_PROPERTY, flag);
        return environment;
    }

    private static void set(MockEnvironment environment, String key, String value) {
        if (!UNSET.equals(value)) {
            environment.setProperty(key, value);
        }
    }
}
