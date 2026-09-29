/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class OpaPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(EnableOpaProperties.class);

    @Configuration
    @EnableConfigurationProperties(OpaProperties.class)
    static class EnableOpaProperties {}

    @Test
    void bindsConfiguredProperties() {
        contextRunner
                .withPropertyValues(
                        "application.opa.url=https://opa.example.internal",
                        "application.opa.decision-path=/v1/data/dispatch/decision",
                        "application.opa.connect-timeout=2s",
                        "application.opa.read-timeout=3s",
                        "application.opa.forwarded-headers[0]=content-type")
                .run(context -> {
                    assertThat(context).hasSingleBean(OpaProperties.class);
                    OpaProperties props = context.getBean(OpaProperties.class);
                    assertThat(props.url()).isEqualTo("https://opa.example.internal");
                    assertThat(props.decisionPath()).isEqualTo("/v1/data/dispatch/decision");
                    assertThat(props.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(props.readTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(props.forwardedHeaders()).containsExactly("content-type");
                    // Not set above, so it must come back off.
                    assertThat(props.enabled()).isFalse();
                });
    }

    /** Enforced endpoints are declared with @Policy, so no path configuration is needed to bind. */
    @Test
    void bindsWithNoPropertiesSet() {
        contextRunner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(OpaProperties.class));
    }

    @Test
    void enabled_bindsWhenExplicitlySwitchedOn() {
        contextRunner.withPropertyValues("application.opa.enabled=true").run(context -> assertThat(
                        context.getBean(OpaProperties.class).enabled())
                .isTrue());
    }
}
