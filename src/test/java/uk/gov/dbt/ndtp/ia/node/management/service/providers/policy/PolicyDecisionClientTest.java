/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductSubscriptionPolicyDecisionDetails;

class PolicyDecisionClientTest {

    private static final PolicyInput INPUT =
            PolicyInputFixture.of("client-1", "configuration", "producer", "/api/v1/configuration/producer");
    private static final OpaProperties PROPERTIES = new OpaProperties(
            true,
            "https://opa.example.internal",
            "/v1/data/dispatch/decision",
            Duration.ofSeconds(2),
            Duration.ofSeconds(3),
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
                restClientBuilder.build(),
                PROPERTIES,
                loggerFor(PROPERTIES),
                outputLoggerFor(PROPERTIES),
                new ObjectMapper());
    }

    @Test
    void allowResult_returnsAllow() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"result\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.evaluate(INPUT)).isEqualTo(PolicyDecision.ALLOW);
    }

    @Test
    void denyResult_returnsDeny() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"result\":false}", MediaType.APPLICATION_JSON));

        assertThat(client.evaluate(INPUT)).isEqualTo(PolicyDecision.DENY);
    }

    @Test
    void objectResult_returnsTheWholeDecisionDocument() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"result": {"allow": true,
                                    "reasons": ["dispatch.resource_fallback"],
                                    "policy": {"id": "product.fallback",
                                               "version": "policies.product.fallback/1.0.0",
                                               "resolution": "resource_fallback"},
                                    "details": {"access_level": "read"}}}""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.evaluate(INPUT))
                .isEqualTo(new PolicyDecision<>(
                        true,
                        List.of("dispatch.resource_fallback"),
                        new PolicyProvenance(
                                "product.fallback", "policies.product.fallback/1.0.0", "resource_fallback"),
                        new PolicyDecisionDetails(Map.of("access_level", "read"))));
    }

    @Test
    void objectResult_denying_stillCarriesItsReasons() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(withSuccess(
                        "{\"result\": {\"allow\": false, \"reasons\": [\"organisation.missing\"]}}",
                        MediaType.APPLICATION_JSON));

        PolicyDecision<PolicyDecisionDetails> output = client.evaluate(INPUT);

        assertThat(output.allow()).isFalse();
        assertThat(output.reasons()).containsExactly("organisation.missing");
    }

    @Test
    void discoveryDetails_carryFieldsAndAttributesSeparately() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(withSuccess(
                        """
                        {"result": {"allow": true,
                                    "details": {"evaluation": "candidate",
                                                "allowed_filtered_fields": ["name", "topic"],
                                                "masked_filtered_fields": ["consumers"],
                                                "allowed_filtered_attributes": ["identifiability"],
                                                "denied_filtered_attributes": ["temporal_resolution"],
                                                "masked_filtered_attributes": ["population_risk_tags"]}}}""",
                        MediaType.APPLICATION_JSON));

        ProductDiscoveryPolicyDecisionDetails details = client.evaluate(
                        INPUT, ProductDiscoveryPolicyDecisionDetails.class)
                .details();

        assertThat(details.allowedFilteredFields()).containsExactly("name", "topic");
        assertThat(details.deniedFilteredFields()).isEmpty();
        assertThat(details.maskedFilteredFields()).containsExactly("consumers");
        assertThat(details.allowedFilteredAttributes()).containsExactly("identifiability");
        assertThat(details.deniedFilteredAttributes()).containsExactly("temporal_resolution");
        assertThat(details.maskedFilteredAttributes()).containsExactly("population_risk_tags");
    }

    @Test
    void missingResult_returnsDeny() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.evaluate(INPUT)).isEqualTo(PolicyDecision.DENY);
    }

    @Test
    void malformedBody_returnsDeny() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

        assertThat(client.evaluate(INPUT)).isEqualTo(PolicyDecision.DENY);
    }

    @Test
    void serverError_returnsDeny() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(withServerError());

        assertThat(client.evaluate(INPUT)).isEqualTo(PolicyDecision.DENY);
    }

    @Test
    void connectionFailure_returnsDeny() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(request -> {
                    throw new IOException("connection refused");
                });

        assertThat(client.evaluate(INPUT)).isEqualTo(PolicyDecision.DENY);
    }

    @Test
    void timeout_returnsDeny() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(request -> {
                    throw new SocketTimeoutException("read timed out");
                });

        assertThat(client.evaluate(INPUT)).isEqualTo(PolicyDecision.DENY);
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
                PROPERTIES.forwardedHeaders(),
                PROPERTIES.logInput(),
                PROPERTIES.logOutput());
        PolicyDecisionClient disabledClient = new PolicyDecisionClient(
                restClientBuilder.build(),
                disabled,
                loggerFor(disabled),
                outputLoggerFor(disabled),
                new ObjectMapper());

        assertThat(disabledClient.evaluate(INPUT)).isEqualTo(PolicyDecision.ALLOW);

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
                PROPERTIES.forwardedHeaders(),
                PROPERTIES.logInput(),
                PROPERTIES.logOutput());
        Logger logger = (Logger) LoggerFactory.getLogger(PolicyDecisionClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new PolicyDecisionClient(
                            restClientBuilder.build(),
                            disabled,
                            loggerFor(disabled),
                            outputLoggerFor(disabled),
                            new ObjectMapper())
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
                            restClientBuilder.build(),
                            PROPERTIES,
                            loggerFor(PROPERTIES),
                            outputLoggerFor(PROPERTIES),
                            new ObjectMapper())
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
                PROPERTIES.forwardedHeaders(),
                PROPERTIES.logInput(),
                true);
        PolicyDecisionClient loggingClient = new PolicyDecisionClient(
                restClientBuilder.build(),
                logOutputEnabled,
                loggerFor(logOutputEnabled),
                outputLoggerFor(logOutputEnabled),
                new ObjectMapper());
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

    private static final String SUBSCRIBE_RESULT =
            """
            {"result": {"allow": true,
                        "reasons": [],
                        "policy": {"id": "product.subscribe",
                                   "version": "policies.product.subscribe/1.0.0",
                                   "resolution": "exact"},
                        "details": {"requires_approval": false,
                                    "max_validity_days": 90,
                                    "permitted_schedule_types": ["cron", "interval"]}}}""";

    private static final PolicyProvenance SUBSCRIBE =
            new PolicyProvenance("product.subscribe", "policies.product.subscribe/1.0.0", "exact");

    @Test
    void objectResult_carriesReasonsProvenanceAndRawDetails() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(withSuccess(SUBSCRIBE_RESULT, MediaType.APPLICATION_JSON));

        PolicyDecision<PolicyDecisionDetails> output = client.evaluate(INPUT);

        assertThat(output.allow()).isTrue();
        assertThat(output.policy()).isEqualTo(SUBSCRIBE);
        assertThat(output.details().additional())
                .containsEntry("requires_approval", false)
                .containsEntry("max_validity_days", 90)
                .containsEntry("permitted_schedule_types", List.of("cron", "interval"));
    }

    @Test
    void declaredDetailsType_readsTheDetailsIntoThatType() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(withSuccess(SUBSCRIBE_RESULT, MediaType.APPLICATION_JSON));

        PolicyDecision<ProductSubscriptionPolicyDecisionDetails> output =
                client.evaluate(INPUT, ProductSubscriptionPolicyDecisionDetails.class);

        assertThat(output.allow()).isTrue();
        assertThat(output.policy()).isEqualTo(SUBSCRIBE);
        assertThat(output.details())
                .isEqualTo(new ProductSubscriptionPolicyDecisionDetails(false, 90, List.of("cron", "interval")));
    }

    @Test
    void declaredDetailsType_keepsFieldsItDoesNotDeclareAsAdditional() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(withSuccess(
                        """
                        {"result": {"allow": true,
                                    "details": {"requires_approval": true, "review_queue": "gold"}}}""",
                        MediaType.APPLICATION_JSON));

        PolicyDecision<ProductSubscriptionPolicyDecisionDetails> output =
                client.evaluate(INPUT, ProductSubscriptionPolicyDecisionDetails.class);

        assertThat(output.details().requiresApproval()).isTrue();
        assertThat(output.details().maxValidityDays()).isNull();
        assertThat(output.details().permittedScheduleTypes()).isEmpty();
        assertThat(output.details().additional()).isEqualTo(Map.of("review_queue", "gold"));
    }

    @Test
    void declaredDetailsType_withNoDetailsReturned_isEmptyDetailsOfThatType() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(withSuccess("{\"result\": {\"allow\": true}}", MediaType.APPLICATION_JSON));

        assertThat(client.evaluate(INPUT, ProductSubscriptionPolicyDecisionDetails.class)
                        .details())
                .isEqualTo(new ProductSubscriptionPolicyDecisionDetails());
    }

    @Test
    void detailsTheDeclaredTypeCannotHold_isDenyKeepingProvenance() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(withSuccess(
                        """
                        {"result": {"allow": true,
                                    "policy": {"id": "product.subscribe",
                                               "version": "policies.product.subscribe/1.0.0",
                                               "resolution": "exact"},
                                    "details": {"max_validity_days": "forever"}}}""",
                        MediaType.APPLICATION_JSON));
        Logger logger = (Logger) LoggerFactory.getLogger(PolicyDecisionClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        PolicyDecision<ProductSubscriptionPolicyDecisionDetails> output;
        try {
            output = client.evaluate(INPUT, ProductSubscriptionPolicyDecisionDetails.class);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(output.allow()).isFalse();
        assertThat(output.reasons()).containsExactly(PolicyDecision.REASON_DETAILS_UNREADABLE);
        assertThat(output.policy()).isEqualTo(SUBSCRIBE);
        assertThat(output.details()).isEqualTo(new ProductSubscriptionPolicyDecisionDetails());
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("product.subscribe")
                    .contains(ProductSubscriptionPolicyDecisionDetails.class.getName());
        });
    }

    @Test
    void declaredDetailsType_doesNotTurnAnUnreachablePdpIntoAnythingButDeny() {
        mockServer
                .expect(requestTo(PROPERTIES.url() + PROPERTIES.decisionPath()))
                .andRespond(withServerError());

        assertThat(client.evaluate(INPUT, ProductSubscriptionPolicyDecisionDetails.class))
                .isEqualTo(PolicyDecision.of(false, ProductSubscriptionPolicyDecisionDetails.class));
    }

    private static PolicyInputLogger loggerFor(OpaProperties properties) {
        return new PolicyInputLogger(properties, new ObjectMapper());
    }

    private static PolicyOutputLogger outputLoggerFor(OpaProperties properties) {
        return new PolicyOutputLogger(properties, new ObjectMapper());
    }
}
