/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicyDecisionSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void request_serializesTheFullInputDocument() throws Exception {
        PolicySubject subject = new PolicySubject(
                PolicySubject.KIND_USER,
                "j.okafor@nhsengland.nhs.uk",
                "catalogue-ui",
                new LinkedHashMap<>(Map.of("azp", "catalogue-ui")),
                new PolicyOrganisation("FEDERATOR_ENV", new LinkedHashMap<>(Map.of("nationality", "GB"))));
        PolicyHttpRequest httpRequest = new PolicyHttpRequest(
                new LinkedHashMap<>(Map.of("content-type", "application/json")),
                new LinkedHashMap<>(),
                "/api/v1/product/discover",
                "POST",
                null);
        PolicyInput input = new PolicyInput(
                subject,
                "discover",
                new PolicyResource("product", "42", new LinkedHashMap<>(Map.of("tier", 3))),
                httpRequest);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(new PolicyDecisionRequest(input)));

        JsonNode in = json.get("input");
        assertThat(in.get("action").asText()).isEqualTo("discover");
        assertThat(in.get("subject").get("kind").asText()).isEqualTo("user");
        assertThat(in.get("subject").get("user_id").asText()).isEqualTo("j.okafor@nhsengland.nhs.uk");
        assertThat(in.get("subject").get("token").get("azp").asText()).isEqualTo("catalogue-ui");
        assertThat(in.get("subject").get("clientId").asText()).isEqualTo("catalogue-ui");
        assertThat(in.get("subject").get("organisation").get("key").asText()).isEqualTo("FEDERATOR_ENV");
        assertThat(in.get("subject")
                        .get("organisation")
                        .get("attributes")
                        .get("nationality")
                        .asText())
                .isEqualTo("GB");
        assertThat(in.get("resource").get("kind").asText()).isEqualTo("product");
        assertThat(in.get("resource").get("id").asText()).isEqualTo("42");
        assertThat(in.get("request").get("path").asText()).isEqualTo("/api/v1/product/discover");
        assertThat(in.get("request").get("method").asText()).isEqualTo("POST");
    }

    @Test
    void request_preservesAttributeJsonTypesRatherThanStringifyingThem() throws Exception {
        PolicyInput input = PolicyInputFixture.of("client-1", "product", "discover")
                .withResource("42", new LinkedHashMap<>(Map.of("tier", 3, "regions", List.of("UK", "EU"))));

        JsonNode attributes = objectMapper
                .readTree(objectMapper.writeValueAsString(new PolicyDecisionRequest(input)))
                .get("input")
                .get("resource")
                .get("attributes");

        assertThat(attributes.get("tier").isInt()).isTrue();
        assertThat(attributes.get("regions").isArray()).isTrue();
        assertThat(attributes.get("regions")).hasSize(2);
    }

    @Test
    void request_omitsNullFieldsRatherThanEmittingNulls() throws Exception {
        PolicyInput input = PolicyInputFixture.of("client-1", "product", "discover");

        String json = objectMapper.writeValueAsString(new PolicyDecisionRequest(input));

        // resource.id and request.body are unset for a whole-endpoint decision
        assertThat(json).doesNotContain("\"id\"").doesNotContain("\"body\"");
    }

    @Test
    void response_deserializesAllowResult() throws Exception {
        PolicyDecisionResponse response = objectMapper.readValue(
                """
                {"result": {"allow": true, "reasons": ["dispatch.resource_fallback"]}}""",
                PolicyDecisionResponse.class);

        assertThat(response.result().allow()).isTrue();
        assertThat(response.result().reasons()).containsExactly("dispatch.resource_fallback");
    }

    @Test
    void response_deserializesDenyResult() throws Exception {
        PolicyDecisionResponse response =
                objectMapper.readValue("{\"result\": {\"allow\": false}}", PolicyDecisionResponse.class);

        assertThat(response.result().allow()).isFalse();
    }

    @Test
    void response_deserializesMissingResultAsNull() throws Exception {
        PolicyDecisionResponse response = objectMapper.readValue("{}", PolicyDecisionResponse.class);

        assertThat(response.result()).isNull();
    }
}
