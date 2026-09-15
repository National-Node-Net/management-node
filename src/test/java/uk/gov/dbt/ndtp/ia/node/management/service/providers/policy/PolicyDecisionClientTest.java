/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;

class PolicyDecisionClientTest {

    private static final PolicyInput INPUT =
            PolicyInputFixture.of("client-1", "configuration", "producer", "/api/v1/configuration/producer");
    private static final OpaProperties PROPERTIES = new OpaProperties(
            true,
            "https://opa.example.internal",
            "/v1/data/management_node/decision",
            Duration.ofSeconds(2),
            Duration.ofSeconds(3),
            List.of("/api/v1/configuration/**"),
            List.of("content-type"),
            false,
            false);

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private PolicyDecisionClient client;

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder().baseUrl(PROPERTIES.url());
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        client = new PolicyDecisionClient(
                restClientBuilder.build(), PROPERTIES, loggerFor(PROPERTIES), outputLoggerFor(PROPERTIES));
    }

    @Test
    void allowResult_returnsAllow() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"result\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.evaluate(INPUT)).isEqualTo(DefaultPolicyDecisionOutput.ALLOW);
    }

    @Test
    void denyResult_returnsDeny() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"result\":false}", MediaType.APPLICATION_JSON));

        assertThat(client.evaluate(INPUT)).isEqualTo(DefaultPolicyDecisionOutput.DENY);
    }

    @Test
    void objectResult_returnsTheWholeDecisionDocument() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"result": {"allow": true,
                                    "allowed_filtered_attributes": ["name"],
                                    "denied_filtered_attributes": ["internal_owner"],
                                    "masked_filtered_attributes": ["contact_email"]}}""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.evaluate(INPUT))
                .isEqualTo(new DefaultPolicyDecisionOutput(
                        true, List.of("name"), List.of("internal_owner"), List.of("contact_email")));
    }

    @Test
    void objectResult_denying_stillCarriesItsAttributeLists() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(withSuccess(
                        "{\"result\": {\"allow\": false, \"denied_filtered_attributes\": [\"name\"]}}",
                        MediaType.APPLICATION_JSON));

        DefaultPolicyDecisionOutput output = client.evaluate(INPUT);

        assertThat(output.allow()).isFalse();
        assertThat(output.deniedFilteredAttributes()).containsExactly("name");
    }

    @Test
    void missingResult_returnsDeny() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.evaluate(INPUT)).isEqualTo(DefaultPolicyDecisionOutput.DENY);
    }

    @Test
    void malformedBody_returnsDeny() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

        assertThat(client.evaluate(INPUT)).isEqualTo(DefaultPolicyDecisionOutput.DENY);
    }

    @Test
    void serverError_returnsDeny() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(withServerError());

        assertThat(client.evaluate(INPUT)).isEqualTo(DefaultPolicyDecisionOutput.DENY);
    }

    @Test
    void connectionFailure_returnsDeny() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(request -> {
                    throw new IOException("connection refused");
                });

        assertThat(client.evaluate(INPUT)).isEqualTo(DefaultPolicyDecisionOutput.DENY);
    }

    @Test
    void timeout_returnsDeny() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(request -> {
                    throw new SocketTimeoutException("read timed out");
                });

        assertThat(client.evaluate(INPUT)).isEqualTo(DefaultPolicyDecisionOutput.DENY);
    }

    @Test
    void requestBody_sendsInputAsExpectedByOpa() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                        .json(
                                "{\"input\":{\"subject\":{\"kind\":\"service\",\"user_id\":\"client-1\",\"clientId\":\"client-1\"},"
                                        + "\"action\":\"producer\",\"resource\":{\"kind\":\"configuration\"},"
                                        + "\"request\":{\"path\":\"/api/v1/configuration/producer\"}}}"))
                .andRespond(withSuccess("{\"result\":true}", MediaType.APPLICATION_JSON));

        client.evaluate(INPUT);

        mockServer.verify();
    }

    @Test
    void opaDisabled_allowsWithoutCallingThePdp() {
        OpaProperties disabled = new OpaProperties(
                false,
                PROPERTIES.url(),
                PROPERTIES.decisionPath(),
                PROPERTIES.connectTimeout(),
                PROPERTIES.readTimeout(),
                PROPERTIES.protectedPaths(),
                PROPERTIES.forwardedHeaders(),
                PROPERTIES.logInput(),
                PROPERTIES.logOutput());
        PolicyDecisionClient disabledClient = new PolicyDecisionClient(
                restClientBuilder.build(), disabled, loggerFor(disabled), outputLoggerFor(disabled));

        assertThat(disabledClient.evaluate(INPUT)).isEqualTo(DefaultPolicyDecisionOutput.ALLOW);

        // No request was expected on the mock server, so any call would have failed verification.
        mockServer.verify();
    }

    @Test
    void opaDisabled_warnsOnceAtStartupThatPolicyIsNotEvaluated() {
        OpaProperties disabled = new OpaProperties(
                false,
                PROPERTIES.url(),
                PROPERTIES.decisionPath(),
                PROPERTIES.connectTimeout(),
                PROPERTIES.readTimeout(),
                PROPERTIES.protectedPaths(),
                PROPERTIES.forwardedHeaders(),
                PROPERTIES.logInput(),
                PROPERTIES.logOutput());
        Logger logger = (Logger) LoggerFactory.getLogger(PolicyDecisionClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new PolicyDecisionClient(
                            restClientBuilder.build(), disabled, loggerFor(disabled), outputLoggerFor(disabled))
                    .warnWhenDisabled();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("OPA is switched off")
                    .contains("application.opa.enabled=false")
                    .contains("NOT be evaluated");
        });
    }

    @Test
    void opaEnabled_doesNotWarnThatPolicyIsSkipped() {
        Logger logger = (Logger) LoggerFactory.getLogger(PolicyDecisionClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new PolicyDecisionClient(
                            restClientBuilder.build(), PROPERTIES, loggerFor(PROPERTIES), outputLoggerFor(PROPERTIES))
                    .warnWhenDisabled();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("OPA is switched off"));
    }

    @Test
    void logOutputEnabled_logsTheDecisionThePdpReturned() {
        OpaProperties logOutputEnabled = new OpaProperties(
                true,
                PROPERTIES.url(),
                PROPERTIES.decisionPath(),
                PROPERTIES.connectTimeout(),
                PROPERTIES.readTimeout(),
                PROPERTIES.protectedPaths(),
                PROPERTIES.forwardedHeaders(),
                PROPERTIES.logInput(),
                true);
        PolicyDecisionClient loggingClient = new PolicyDecisionClient(
                restClientBuilder.build(),
                logOutputEnabled,
                loggerFor(logOutputEnabled),
                outputLoggerFor(logOutputEnabled));
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(withSuccess("{\"result\":true}", MediaType.APPLICATION_JSON));
        Logger logger = (Logger) LoggerFactory.getLogger(PolicyOutputLogger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            loggingClient.evaluate(INPUT);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("PDP decision response <- ALLOW")
                .contains("action               : producer"));
    }

    @Test
    void logOutputDisabled_logsNothing() {
        Logger logger = (Logger) LoggerFactory.getLogger(PolicyOutputLogger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(withSuccess("{\"result\":true}", MediaType.APPLICATION_JSON));

        try {
            client.evaluate(INPUT);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).isEmpty();
    }

    private static PolicyInputLogger loggerFor(OpaProperties properties) {
        return new PolicyInputLogger(properties, new ObjectMapper());
    }

    private static PolicyOutputLogger outputLoggerFor(OpaProperties properties) {
        return new PolicyOutputLogger(properties, new ObjectMapper());
    }
}
