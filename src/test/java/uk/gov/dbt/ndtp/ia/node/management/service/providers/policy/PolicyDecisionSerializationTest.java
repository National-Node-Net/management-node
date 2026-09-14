/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PolicyDecisionSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void request_serializesWithAllAttributes() throws Exception {
        PolicyDecisionRequest request = new PolicyDecisionRequest(
                new PolicyInput("client-1", "FEDERATOR_ENV", "42", "/api/v1/configuration/producer", "GET"));

        String json = objectMapper.writeValueAsString(request);

        assertThat(json)
                .isEqualTo(
                        "{\"input\":{\"clientId\":\"client-1\",\"organisation\":\"FEDERATOR_ENV\",\"organisationId\":\"42\","
                                + "\"resource\":\"/api/v1/configuration/producer\",\"action\":\"GET\"}}");
    }

    @Test
    void request_omitsOrganisationFieldsWhenNull() throws Exception {
        PolicyDecisionRequest request = new PolicyDecisionRequest(
                new PolicyInput("client-1", null, null, "/api/v1/configuration/producer", "GET"));

        String json = objectMapper.writeValueAsString(request);

        assertThat(json)
                .doesNotContain("organisation")
                .doesNotContain("organisationId")
                .isEqualTo(
                        "{\"input\":{\"clientId\":\"client-1\",\"resource\":\"/api/v1/configuration/producer\",\"action\":\"GET\"}}");
    }

    @Test
    void request_keepsTokenOrganisationWhenCertificateIdAbsent() throws Exception {
        PolicyDecisionRequest request = new PolicyDecisionRequest(
                new PolicyInput("client-1", "FEDERATOR_ENV", null, "/api/v1/configuration/producer", "GET"));

        String json = objectMapper.writeValueAsString(request);

        assertThat(json).contains("\"organisation\":\"FEDERATOR_ENV\"").doesNotContain("organisationId");
    }

    @Test
    void response_deserializesAllowResult() throws Exception {
        PolicyDecisionResponse response = objectMapper.readValue("{\"result\":true}", PolicyDecisionResponse.class);

        assertThat(response.result()).isTrue();
    }

    @Test
    void response_deserializesDenyResult() throws Exception {
        PolicyDecisionResponse response = objectMapper.readValue("{\"result\":false}", PolicyDecisionResponse.class);

        assertThat(response.result()).isFalse();
    }

    @Test
    void response_deserializesMissingResultAsNull() throws Exception {
        PolicyDecisionResponse response = objectMapper.readValue("{}", PolicyDecisionResponse.class);

        assertThat(response.result()).isNull();
    }
}
