/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * A freshly-built (unpopulated) {@link ProducerDTO}/{@link ConsumerDTO}/{@link
 * ProductConsumerDTO} must serialise its new policy attribute fields as {@code []}, never {@code
 * null} or an omitted field - see design.md's "Empty, not null, arrays" decision.
 */
class PolicyAttributeFieldsSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void policyAttribute_serialisesNamespaceNameAndValueOnly() throws Exception {
        String json = objectMapper.writeValueAsString(PolicyAttributeDTO.builder()
                .namespace("policy")
                .name("risk-tier")
                .value("gold")
                .build());

        assertThat(json).isEqualTo("{\"namespace\":\"policy\",\"name\":\"risk-tier\",\"value\":\"gold\"}");
    }

    @Test
    void producerDto_policyAttributesSerialisesAsEmptyArray() throws Exception {
        JsonNode json = objectMapper.readTree(
                objectMapper.writeValueAsString(ProducerDTO.builder().build()));

        assertThat(json.has("policyAttributes")).isTrue();
        assertThat(json.get("policyAttributes").isArray()).isTrue();
        assertThat(json.get("policyAttributes")).isEmpty();
    }

    @Test
    void consumerDto_policyAttributeFieldsSerialiseAsEmptyArrays() throws Exception {
        JsonNode json = objectMapper.readTree(
                objectMapper.writeValueAsString(ConsumerDTO.builder().build()));

        assertThat(json.get("policyAttributes").isArray()).isTrue();
        assertThat(json.get("policyAttributes")).isEmpty();
        assertThat(json.get("organisationPolicyAttributes").isArray()).isTrue();
        assertThat(json.get("organisationPolicyAttributes")).isEmpty();
    }

    @Test
    void productConsumerDto_policyAttributesSerialisesAsEmptyArray() throws Exception {
        JsonNode json = objectMapper.readTree(
                objectMapper.writeValueAsString(ProductConsumerDTO.builder().build()));

        assertThat(json.get("policyAttributes").isArray()).isTrue();
        assertThat(json.get("policyAttributes")).isEmpty();
    }
}
