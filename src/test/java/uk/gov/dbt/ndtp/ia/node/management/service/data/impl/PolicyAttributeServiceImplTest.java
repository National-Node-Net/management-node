/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.PolicyAttributeDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeDefinition;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeDefinitionScope;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeValue;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.AttributeValueRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeScope;

@ExtendWith(MockitoExtension.class)
class PolicyAttributeServiceImplTest {

    @Mock
    private AttributeValueRepository attributeValueRepository;

    private PolicyAttributeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PolicyAttributeServiceImpl(attributeValueRepository, new ObjectMapper());
    }

    private static AttributeValue attributeValue(String namespace, String name, String dataType, String rawJson) {
        AttributeDefinition definition = new AttributeDefinition();
        definition.setNamespace(namespace);
        definition.setName(name);
        definition.setDataType(dataType);

        AttributeDefinitionScope binding = new AttributeDefinitionScope();
        binding.setAttributeDefinition(definition);

        AttributeValue value = new AttributeValue();
        value.setAttributeDefinitionScope(binding);
        value.setValue(rawJson);
        return value;
    }

    @Test
    void findAttributes_mapsNamespaceDotNameValueAndType() {
        when(attributeValueRepository.findLiveByEntityIdAndScopeCode(10L, "PRODUCER"))
                .thenReturn(List.of(attributeValue("policy", "risk-tier", "STRING", "\"gold\"")));

        List<PolicyAttributeDTO> result = service.findAttributes(10L, PolicyAttributeScope.PRODUCER);

        assertThat(result).hasSize(1);
        PolicyAttributeDTO dto = result.get(0);
        assertThat(dto.getName()).isEqualTo("policy.risk-tier");
        assertThat(dto.getValue()).isEqualTo("gold");
        assertThat(dto.getType()).isEqualTo("STRING");
    }

    @Test
    void findAttributes_returnsEmptyListWhenRepositoryFindsNothing() {
        when(attributeValueRepository.findLiveByEntityIdAndScopeCode(11L, "CONSUMER"))
                .thenReturn(List.of());

        List<PolicyAttributeDTO> result = service.findAttributes(11L, PolicyAttributeScope.CONSUMER);

        assertThat(result).isEmpty();
    }

    @Test
    void findAttributes_rendersNonStringValueAsPlainText() {
        when(attributeValueRepository.findLiveByEntityIdAndScopeCode(12L, "ORGANISATION"))
                .thenReturn(List.of(
                        attributeValue("policy", "priority", "INTEGER", "42"),
                        attributeValue("policy", "enabled", "BOOLEAN", "true")));

        List<PolicyAttributeDTO> result = service.findAttributes(12L, PolicyAttributeScope.ORGANISATION);

        assertThat(result).extracting(PolicyAttributeDTO::getValue).containsExactly("42", "true");
    }

    @Test
    void findAttributes_fallsBackToRawTextOnMalformedStoredValue() {
        when(attributeValueRepository.findLiveByEntityIdAndScopeCode(13L, "SUBSCRIPTION"))
                .thenReturn(List.of(attributeValue("policy", "broken", "STRING", "not-valid-json{")));

        List<PolicyAttributeDTO> result = service.findAttributes(13L, PolicyAttributeScope.SUBSCRIPTION);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getValue()).isEqualTo("not-valid-json{");
    }
}
