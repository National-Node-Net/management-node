/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.organisation.OrganisationDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.policy.PolicyAttributeDTO;

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
    void organisationDto_serialisesNameKeyAndPolicyAttributes() throws Exception {
        OrganisationDTO organisation = OrganisationDTO.builder()
                .name("Environment Agency (ENV)")
                .key("ENV")
                .build();
        organisation
                .getPolicyAttributes()
                .add(PolicyAttributeDTO.builder()
                        .namespace("policy")
                        .name("jurisdictions")
                        .value("England")
                        .build());

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(organisation));

        assertThat(json.get("name").asText()).isEqualTo("Environment Agency (ENV)");
        assertThat(json.get("key").asText()).isEqualTo("ENV");
        assertThat(json.get("policyAttributes")).hasSize(1);
        assertThat(json.get("policyAttributes").get(0).get("name").asText()).isEqualTo("jurisdictions");
    }

    @Test
    void organisationDto_policyAttributesSerialisesAsEmptyArrayWhenUnpopulated() throws Exception {
        JsonNode json = objectMapper.readTree(
                objectMapper.writeValueAsString(OrganisationDTO.builder().build()));

        assertThat(json.get("policyAttributes").isArray()).isTrue();
        assertThat(json.get("policyAttributes")).isEmpty();
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
        // organisation is a whole DTO now, not a flat attribute list, and is null until resolved
        assertThat(json.has("organisation")).isTrue();
        assertThat(json.get("organisation").isNull()).isTrue();
    }

    @Test
    void productDto_policyAttributesSerialisesAsEmptyArray() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(new ProductDTO()));

        assertThat(json.has("policyAttributes")).isTrue();
        assertThat(json.get("policyAttributes").isArray()).isTrue();
        assertThat(json.get("policyAttributes")).isEmpty();
    }

    @Test
    void producerConfigDto_carriesOrganisation() throws Exception {
        ProducerConfigDTO config = ProducerConfigDTO.builder()
                .clientId("FEDERATOR_ENV")
                .organisation(OrganisationDTO.builder()
                        .name("Environment Agency (ENV)")
                        .key("ENV")
                        .build())
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(config));

        assertThat(json.get("organisation").get("key").asText()).isEqualTo("ENV");
        assertThat(json.get("organisation").get("name").asText()).isEqualTo("Environment Agency (ENV)");
        assertThat(json.get("organisation").get("policyAttributes").isArray()).isTrue();
    }

    @Test
    void productConsumerDto_policyAttributesSerialisesAsEmptyArray() throws Exception {
        JsonNode json = objectMapper.readTree(
                objectMapper.writeValueAsString(ProductConsumerDTO.builder().build()));

        assertThat(json.get("policyAttributes").isArray()).isTrue();
        assertThat(json.get("policyAttributes")).isEmpty();
    }
}
