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

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;

class PolicyDecisionClientTest {

    private static final PolicyInput INPUT =
            new PolicyInput("client-1", null, null, "/api/v1/configuration/producer", "GET");
    private static final OpaProperties PROPERTIES = new OpaProperties(
            "https://opa.example.internal",
            "/v1/data/management_node/allow",
            Duration.ofSeconds(2),
            Duration.ofSeconds(3),
            List.of("/api/v1/configuration/**"));

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private PolicyDecisionClient client;

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder().baseUrl(PROPERTIES.url());
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        client = new PolicyDecisionClient(restClientBuilder.build(), PROPERTIES);
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
                .andExpect(
                        org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                                .json(
                                        "{\"input\":{\"clientId\":\"client-1\",\"resource\":\"/api/v1/configuration/producer\",\"action\":\"GET\"}}"))
                .andRespond(withSuccess("{\"result\":true}", MediaType.APPLICATION_JSON));

        client.evaluate(INPUT);

        mockServer.verify();
    }
}
