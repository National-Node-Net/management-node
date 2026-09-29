/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;

class PolicyOutputLoggerTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(PolicyOutputLogger.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private static OpaProperties properties(boolean logOutput) {
        return new OpaProperties(
                true,
                "http://opa:8181",
                "/v1/data/dispatch/decision",
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                List.of("content-type"),
                false,
                logOutput);
    }

    private static PolicyOutputLogger loggerWith(boolean logOutput) {
        return new PolicyOutputLogger(properties(logOutput), new ObjectMapper());
    }

    private String onlyMessage() {
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0).getFormattedMessage();
    }

    @Test
    void disabled_logsNothing() {
        PolicyOutputLogger outputLogger = loggerWith(false);
        PolicyInput input = PolicyInputFixture.of("client-1", "product", "discover");

        outputLogger.logOutput(input, PolicyDecision.ALLOW);

        assertThat(outputLogger.isEnabled()).isFalse();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void enabled_logsVerdictActionAndResource() {
        PolicyOutputLogger outputLogger = loggerWith(true);
        PolicyInput candidate =
                PolicyInputFixture.of("client-1", "product", "discover").withResource("42", Map.of());

        outputLogger.logOutput(candidate, PolicyDecision.ALLOW);

        assertThat(onlyMessage())
                .contains("PDP decision response <- ALLOW")
                .contains("action               : discover")
                .contains("resource             : product id=42")
                .contains("\"allow\" : true");
    }

    @Test
    void enabled_logsReasonsProvenanceAndDetails() {
        PolicyOutputLogger outputLogger = loggerWith(true);
        PolicyDecision<PolicyDecisionDetails> output = new PolicyDecision<>(
                true,
                List.of("dispatch.resource_fallback"),
                new PolicyProvenance("product.fallback", "policies.product.fallback/1.0.0", "resource_fallback"),
                new PolicyDecisionDetails(Map.of("access_level", "read")));
        PolicyInput input = PolicyInputFixture.of("client-1", "product", "view");

        outputLogger.logOutput(input, output);

        assertThat(onlyMessage())
                .contains("reasons              : [dispatch.resource_fallback]")
                .contains("policy               : id=product.fallback version=policies.product.fallback/1.0.0"
                        + " resolution=resource_fallback")
                .contains("details              : {\"access_level\":\"read\"}");
    }

    @Test
    void deny_isLoggedAsDeny() {
        PolicyOutputLogger outputLogger = loggerWith(true);
        PolicyInput input = PolicyInputFixture.of("client-1", "product", "discover");

        outputLogger.logOutput(input, PolicyDecision.DENY);

        assertThat(onlyMessage()).contains("PDP decision response <- DENY");
    }

    /** A logging failure must not turn into a failed authorisation decision. */
    @Test
    void unserialisablePayload_isReportedInPlaceOfThePayloadRatherThanThrowing() {
        ObjectMapper failingMapper = new ObjectMapper();
        SimpleModule failingModule = new SimpleModule();
        failingModule.addSerializer(PolicyDecision.class, new StdSerializer<>(PolicyDecision.class) {
            @Override
            @SuppressWarnings("rawtypes")
            public void serialize(PolicyDecision value, JsonGenerator gen, SerializerProvider provider)
                    throws IOException {
                throw new IOException("boom");
            }
        });
        failingMapper.registerModule(failingModule);
        PolicyOutputLogger outputLogger = new PolicyOutputLogger(properties(true), failingMapper);
        PolicyInput input = PolicyInputFixture.of("client-1", "product", "discover");

        outputLogger.logOutput(input, PolicyDecision.ALLOW);

        // Jackson wraps the cause, so the reported text carries its message rather than only "boom".
        assertThat(onlyMessage()).contains("<not serialisable:").contains("boom");
    }

    @Test
    void nullInput_isIgnored() {
        loggerWith(true).logOutput(null, PolicyDecision.ALLOW);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void nullOutput_isIgnored() {
        loggerWith(true).logOutput(PolicyInputFixture.of("client-1", "product", "discover"), null);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void enabled_warnsOnceAtStartup() {
        loggerWith(true).warnWhenEnabled();

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("application.opa.log-output=true")
                    .contains("candidate");
        });
    }

    @Test
    void disabled_doesNotWarnAtStartup() {
        loggerWith(false).warnWhenEnabled();

        assertThat(appender.list).isEmpty();
    }
}
