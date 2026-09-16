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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.policy.PolicyAttributeDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeDefinition;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeDefinitionScope;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeValue;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.policy.PolicyAttributeValueRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeScopeCode;

@ExtendWith(MockitoExtension.class)
class PolicyAttributeServiceImplTest {

    @Mock
    private PolicyAttributeValueRepository policyAttributeValueRepository;

    private PolicyAttributeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PolicyAttributeServiceImpl(policyAttributeValueRepository, new ObjectMapper());
    }

    private static PolicyAttributeValue attributeValue(String namespace, String name, String dataType, String rawJson) {
        return attributeValue(namespace, name, dataType, rawJson, 1L, false);
    }

    /** As above, with an explicit definition id and {@code multi_valued} flag. */
    private static PolicyAttributeValue attributeValue(
            String namespace, String name, String dataType, String rawJson, Long definitionId, boolean multiValued) {
        PolicyAttributeDefinition definition = new PolicyAttributeDefinition();
        definition.setId(definitionId);
        definition.setMultiValued(multiValued);
        definition.setNamespace(namespace);
        definition.setName(name);
        definition.setDataType(dataType);

        PolicyAttributeDefinitionScope binding = new PolicyAttributeDefinitionScope();
        binding.setAttributeDefinition(definition);

        PolicyAttributeValue value = new PolicyAttributeValue();
        value.setAttributeDefinitionScope(binding);
        value.setValue(rawJson);
        return value;
    }

    @Test
    void findAttributes_mapsNamespaceNameAndValueAsSeparateFields() {
        when(policyAttributeValueRepository.findLiveByEntityIdAndScopeCode(10L, "PRODUCER"))
                .thenReturn(List.of(attributeValue("policy", "risk-tier", "STRING", "\"gold\"")));

        List<PolicyAttributeDTO> result = service.findAttributes(10L, PolicyAttributeScopeCode.PRODUCER);

        assertThat(result).hasSize(1);
        PolicyAttributeDTO dto = result.get(0);
        assertThat(dto.getNamespace()).isEqualTo("policy");
        assertThat(dto.getName()).isEqualTo("risk-tier");
        assertThat(dto.getValue()).isEqualTo("gold");
    }

    @Test
    void findAttributes_returnsEmptyListWhenRepositoryFindsNothing() {
        when(policyAttributeValueRepository.findLiveByEntityIdAndScopeCode(11L, "CONSUMER"))
                .thenReturn(List.of());

        List<PolicyAttributeDTO> result = service.findAttributes(11L, PolicyAttributeScopeCode.CONSUMER);

        assertThat(result).isEmpty();
    }

    @Test
    void findAttributes_rendersNonStringValueAsPlainText() {
        when(policyAttributeValueRepository.findLiveByEntityIdAndScopeCode(12L, "ORGANISATION"))
                .thenReturn(List.of(
                        attributeValue("policy", "priority", "INTEGER", "42"),
                        attributeValue("policy", "enabled", "BOOLEAN", "true")));

        List<PolicyAttributeDTO> result = service.findAttributes(12L, PolicyAttributeScopeCode.ORGANISATION);

        assertThat(result).extracting(PolicyAttributeDTO::getValue).containsExactly("42", "true");
    }

    @Test
    void findAttributes_fallsBackToRawTextOnMalformedStoredValue() {
        when(policyAttributeValueRepository.findLiveByEntityIdAndScopeCode(13L, "SUBSCRIPTION"))
                .thenReturn(List.of(attributeValue("policy", "broken", "STRING", "not-valid-json{")));

        List<PolicyAttributeDTO> result = service.findAttributes(13L, PolicyAttributeScopeCode.SUBSCRIPTION);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getValue()).isEqualTo("not-valid-json{");
    }

    @Test
    void findAttributeMap_collectsEveryValueOfAMultiValuedAttribute() {
        when(policyAttributeValueRepository.findLiveByEntityIdAndScopeCode(10L, "PRODUCT"))
                .thenReturn(List.of(
                        attributeValue("policy", "regions", "STRING", "\"UK\"", 1L, true),
                        attributeValue("policy", "regions", "STRING", "\"EU\"", 1L, true),
                        attributeValue("policy", "regions", "STRING", "\"US\"", 1L, true)));

        Map<String, Object> result = service.findAttributeMap(10L, PolicyAttributeScopeCode.PRODUCT);

        assertThat(result).containsEntry("regions", List.of("UK", "EU", "US"));
    }

    @Test
    void findAttributeMap_multiValuedWithOneValueIsStillAList() {
        when(policyAttributeValueRepository.findLiveByEntityIdAndScopeCode(10L, "PRODUCT"))
                .thenReturn(List.of(attributeValue("policy", "regions", "STRING", "\"UK\"", 1L, true)));

        Map<String, Object> result = service.findAttributeMap(10L, PolicyAttributeScopeCode.PRODUCT);

        // The shape follows the definition, not the row count, so a policy can always index it.
        assertThat(result).containsEntry("regions", List.of("UK"));
    }

    @Test
    void findAttributeMap_singleValuedStaysAScalar() {
        when(policyAttributeValueRepository.findLiveByEntityIdAndScopeCode(10L, "PRODUCT"))
                .thenReturn(List.of(attributeValue("policy", "classification", "STRING", "\"OFFICIAL\"", 1L, false)));

        Map<String, Object> result = service.findAttributeMap(10L, PolicyAttributeScopeCode.PRODUCT);

        assertThat(result).containsEntry("classification", "OFFICIAL");
    }

    @Test
    void findAttributeMap_doesNotNestWhenOneRowAlreadyHoldsAnArray() {
        when(policyAttributeValueRepository.findLiveByEntityIdAndScopeCode(10L, "PRODUCT"))
                .thenReturn(List.of(
                        attributeValue("policy", "regions", "STRING", "[\"UK\",\"EU\"]", 1L, true),
                        attributeValue("policy", "regions", "STRING", "\"US\"", 1L, true)));

        Map<String, Object> result = service.findAttributeMap(10L, PolicyAttributeScopeCode.PRODUCT);

        assertThat(result).containsEntry("regions", List.of("UK", "EU", "US"));
    }

    @Test
    void findAttributeMap_keepsJsonTypesForNumbersAndBooleans() {
        when(policyAttributeValueRepository.findLiveByEntityIdAndScopeCode(10L, "PRODUCT"))
                .thenReturn(List.of(
                        attributeValue("policy", "tier", "INTEGER", "3", 1L, false),
                        attributeValue("policy", "sensitive", "BOOLEAN", "true", 2L, false),
                        attributeValue("policy", "scores", "INTEGER", "1", 3L, true),
                        attributeValue("policy", "scores", "INTEGER", "2", 3L, true)));

        Map<String, Object> result = service.findAttributeMap(10L, PolicyAttributeScopeCode.PRODUCT);

        assertThat(result).containsEntry("tier", 3).containsEntry("sensitive", true);
        assertThat(result).containsEntry("scores", List.of(1, 2));
    }

    @Test
    void findAttributeMap_singleValuedWithSeveralLiveValuesKeepsTheFirst() {
        when(policyAttributeValueRepository.findLiveByEntityIdAndScopeCode(10L, "PRODUCT"))
                .thenReturn(List.of(
                        attributeValue("policy", "classification", "STRING", "\"OFFICIAL\"", 1L, false),
                        attributeValue("policy", "classification", "STRING", "\"SECRET\"", 1L, false)));

        Map<String, Object> result = service.findAttributeMap(10L, PolicyAttributeScopeCode.PRODUCT);

        // A data anomaly: the declared shape is scalar, so it stays scalar.
        assertThat(result).containsEntry("classification", "OFFICIAL");
    }

    @Test
    void findAttributeMap_sameNameInTwoNamespacesKeepsTheFirstDefinition() {
        when(policyAttributeValueRepository.findLiveByEntityIdAndScopeCode(10L, "PRODUCT"))
                .thenReturn(List.of(
                        attributeValue("policy", "tier", "STRING", "\"gold\"", 1L, false),
                        attributeValue("other", "tier", "STRING", "\"bronze\"", 2L, false)));

        Map<String, Object> result = service.findAttributeMap(10L, PolicyAttributeScopeCode.PRODUCT);

        assertThat(result).containsEntry("tier", "gold").hasSize(1);
    }

    @Test
    void findAttributeMap_returnsEmptyMapWhenRepositoryFindsNothing() {
        when(policyAttributeValueRepository.findLiveByEntityIdAndScopeCode(10L, "PRODUCT"))
                .thenReturn(List.of());

        assertThat(service.findAttributeMap(10L, PolicyAttributeScopeCode.PRODUCT))
                .isEmpty();
    }
}
