/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;

class PolicyInputLoggerTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(PolicyInputLogger.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private static OpaProperties properties(boolean logInput) {
        return new OpaProperties(
                true,
                "http://opa:8181",
                "/v1/data/dispatch/decision",
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                List.of("content-type"),
                logInput,
                false);
    }

    private static PolicyInputLogger loggerWith(boolean logInput) {
        return new PolicyInputLogger(properties(logInput), new ObjectMapper());
    }

    private String onlyMessage() {
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0).getFormattedMessage();
    }

    @Test
    void disabled_logsNothing() {
        PolicyInputLogger inputLogger = loggerWith(false);

        inputLogger.logInput(PolicyInputFixture.of("client-1", "product", "discover"));

        assertThat(inputLogger.isEnabled()).isFalse();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void enabled_logsSummaryAndPayloadIncludingTheDecisionUri() {
        PolicyInputLogger inputLogger = loggerWith(true);

        inputLogger.logInput(PolicyInputFixture.of("client-1", "product", "discover"));

        String message = onlyMessage();
        assertThat(message)
                .contains("http://opa:8181/v1/data/dispatch/decision")
                .contains("subject.clientId     : client-1")
                .contains("action               : discover")
                .contains("resource             : product")
                .contains("request              : POST /api/v1/product/discover")
                .contains("\"input\"");
    }

    /** A body the caller never passed must not read as a body that serialised to nothing. */
    @Test
    void noBodySupplied_isReportedAsNoneRatherThanAnEmptyDocument() {
        PolicyInputLogger inputLogger = loggerWith(true);

        inputLogger.logInput(PolicyInputFixture.of("client-1", "configuration", "producer"));

        assertThat(onlyMessage()).contains("request.body         : <none>");
    }

    @Test
    void bodySupplied_isLoggedAsCompactJson() {
        PolicyInputLogger inputLogger = loggerWith(true);
        PolicyInput input = PolicyInputFixture.of("client-1", "product", "discover");
        PolicyHttpRequest request = new PolicyHttpRequest(
                Map.of(), Map.of("page", List.of("1")), input.request().path(), "POST", Map.of("topic", "planning"));
        PolicyInput withBody = new PolicyInput(input.subject(), input.action(), input.resource(), request);

        inputLogger.logInput(withBody);

        assertThat(onlyMessage())
                .contains("request.body         : {\"topic\":\"planning\"}")
                .contains("request.query        : {page=[1]}");
    }

    @Test
    void resourceId_andAttributesAreLoggedForPerCandidateDecisions() {
        PolicyInputLogger inputLogger = loggerWith(true);
        PolicyInput candidate = PolicyInputFixture.of("client-1", "product", "discover")
                .withResource("42", Map.of("classification", "OFFICIAL"));

        inputLogger.logInput(candidate);

        assertThat(onlyMessage())
                .contains("id=42")
                .contains("attributes={classification=OFFICIAL}")
                .contains("\"classification\" : \"OFFICIAL\"");
    }

    @Test
    void nullInput_isIgnored() {
        loggerWith(true).logInput(null);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void enabled_warnsOnceAtStartupThatTokenClaimsReachTheLog() {
        loggerWith(true).warnWhenEnabled();

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("application.opa.log-input=true")
                    .contains("token claims");
        });
    }

    @Test
    void disabled_doesNotWarnAtStartup() {
        loggerWith(false).warnWhenEnabled();

        assertThat(appender.list).isEmpty();
    }
}
