/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.PolicyAttributeDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeDefinition;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeValue;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.AttributeValueRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeScope;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeService;

@Service
public class PolicyAttributeServiceImpl implements PolicyAttributeService {

    private final AttributeValueRepository attributeValueRepository;
    private final ObjectMapper objectMapper;

    public PolicyAttributeServiceImpl(AttributeValueRepository attributeValueRepository, ObjectMapper objectMapper) {
        this.attributeValueRepository = attributeValueRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<PolicyAttributeDTO> findAttributes(Long entityId, PolicyAttributeScope scope) {
        return attributeValueRepository.findLiveByEntityIdAndScopeCode(entityId, scope.code()).stream()
                .map(this::toDto)
                .toList();
    }

    private PolicyAttributeDTO toDto(AttributeValue attributeValue) {
        AttributeDefinition definition =
                attributeValue.getAttributeDefinitionScope().getAttributeDefinition();
        return PolicyAttributeDTO.builder()
                .namespace(definition.getNamespace())
                .name(definition.getName())
                .value(renderValue(attributeValue.getValue()))
                .build();
    }

    /**
     * Renders a stored {@code policy_attribute_value.value} (JSON text) as plain text - a JSON string's
     * quotes are stripped, a number/boolean is rendered as-is. Falls back to the raw stored text
     * on a parse failure rather than throwing: this is a display-layer concern, not a policy
     * decision to compile a predicate against, so failing softly here is the right trade-off (see
     * design.md - Risks).
     */
    private String renderValue(String rawJson) {
        try {
            return objectMapper.readTree(rawJson).asText();
        } catch (JsonProcessingException e) {
            return rawJson;
        }
    }
}
