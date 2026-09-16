/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class OpaClientConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(OpaClientConfig.class);

    private Logger logger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(OpaClientConfig.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    @Test
    void createsRestClientBeanFromConfiguredProperties() {
        contextRunner
                .withPropertyValues(
                        "application.opa.url=https://opa.example.internal",
                        "application.opa.decision-path=/v1/data/dispatch/decision",
                        "application.opa.connect-timeout=2s",
                        "application.opa.read-timeout=3s")
                .run(context -> assertThat(context).hasSingleBean(RestClient.class));
    }

    @Test
    void nonHttpsUrl_logsWarningWhenOpaIsEnabled() {
        contextRunner
                .withPropertyValues(
                        "application.opa.enabled=true",
                        "application.opa.url=http://opa.example.internal",
                        "application.opa.decision-path=/v1/data/dispatch/decision",
                        "application.opa.connect-timeout=2s",
                        "application.opa.read-timeout=3s")
                .run(context -> assertThat(logAppender.list).anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains("http://opa.example.internal");
                }));
    }

    @Test
    void httpsUrl_doesNotLogWarning() {
        contextRunner
                .withPropertyValues(
                        "application.opa.enabled=true",
                        "application.opa.url=https://opa.example.internal",
                        "application.opa.decision-path=/v1/data/dispatch/decision",
                        "application.opa.connect-timeout=2s",
                        "application.opa.read-timeout=3s")
                .run(context -> assertThat(warnings()).isEmpty());
    }

    @Test
    void opaDisabled_doesNotWarnAboutNonHttpsUrl() {
        contextRunner
                .withPropertyValues(
                        "application.opa.url=http://opa.example.internal",
                        "application.opa.decision-path=/v1/data/dispatch/decision",
                        "application.opa.connect-timeout=2s",
                        "application.opa.read-timeout=3s")
                .run(context -> {
                    // The client is still built, it just never gets called - so a TLS warning
                    // would only add noise beside PolicyDecisionClient's "OPA is switched off".
                    assertThat(context).hasSingleBean(RestClient.class);
                    assertThat(warnings()).isEmpty();
                });
    }

    @Test
    void logsOpaUrlAndEnabledStatusAtStartup() {
        contextRunner
                .withPropertyValues(
                        "application.opa.enabled=true",
                        "application.opa.url=https://opa.example.internal",
                        "application.opa.decision-path=/v1/data/dispatch/decision",
                        "application.opa.connect-timeout=2s",
                        "application.opa.read-timeout=3s")
                .run(context -> assertThat(logAppender.list).anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.INFO);
                    assertThat(event.getFormattedMessage())
                            .contains("ENABLED")
                            .contains("https://opa.example.internal")
                            .contains("/v1/data/dispatch/decision");
                }));
    }

    @Test
    void logsOpaUrlAndDisabledStatusAtStartup() {
        contextRunner
                .withPropertyValues(
                        "application.opa.url=http://opa.example.internal",
                        "application.opa.decision-path=/v1/data/dispatch/decision",
                        "application.opa.connect-timeout=2s",
                        "application.opa.read-timeout=3s")
                .run(context -> assertThat(logAppender.list).anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.INFO);
                    assertThat(event.getFormattedMessage())
                            .contains("DISABLED")
                            .contains("http://opa.example.internal");
                }));
    }

    private java.util.List<ILoggingEvent> warnings() {
        return logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .toList();
    }
}
