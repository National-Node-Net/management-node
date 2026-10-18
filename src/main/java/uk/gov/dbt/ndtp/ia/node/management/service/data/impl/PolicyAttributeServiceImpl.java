/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.policy.PolicyAttributeDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeDefinition;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeValue;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.policy.PolicyAttributeValueRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeScopeCode;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeService;

@Service
@Slf4j
public class PolicyAttributeServiceImpl implements PolicyAttributeService {

    private final PolicyAttributeValueRepository policyAttributeValueRepository;
    private final ObjectMapper objectMapper;

    public PolicyAttributeServiceImpl(
            PolicyAttributeValueRepository policyAttributeValueRepository, ObjectMapper objectMapper) {
        this.policyAttributeValueRepository = policyAttributeValueRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<PolicyAttributeDTO> findAttributes(Long entityId, PolicyAttributeScopeCode scope) {
        return policyAttributeValueRepository.findLiveByEntityIdAndScopeCode(entityId, scope.code()).stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public Map<String, Object> findAttributeMap(Long entityId, PolicyAttributeScopeCode scope) {
        Map<String, Long> definitionIdByName = new LinkedHashMap<>();
        Map<String, Boolean> multiValuedByName = new LinkedHashMap<>();
        Map<String, List<Object>> valuesByName = new LinkedHashMap<>();

        for (PolicyAttributeValue attributeValue :
                policyAttributeValueRepository.findLiveByEntityIdAndScopeCode(entityId, scope.code())) {
            PolicyAttributeDefinition definition =
                    attributeValue.getAttributeDefinitionScope().getAttributeDefinition();
            String name = definition.getName();

            Long firstDefinitionId = definitionIdByName.putIfAbsent(name, definition.getId());
            if (firstDefinitionId != null && !firstDefinitionId.equals(definition.getId())) {
                log.warn(
                        "Duplicate policy attribute name {} for {} {} across namespaces; keeping the first definition",
                        name,
                        scope.code(),
                        entityId);
                continue;
            }

            Object value = readValue(attributeValue.getValue());
            if (value == null) {
                continue;
            }
            multiValuedByName.putIfAbsent(name, Boolean.TRUE.equals(definition.getMultiValued()));
            valuesByName.computeIfAbsent(name, key -> new ArrayList<>()).addAll(flatten(value));
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        valuesByName.forEach((name, values) -> {
            if (values.isEmpty()) {
                return;
            }
            if (Boolean.TRUE.equals(multiValuedByName.get(name))) {
                attributes.put(name, Collections.unmodifiableList(values));
                return;
            }
            if (values.size() > 1) {
                log.warn(
                        "Single-valued policy attribute {} has {} live values for {} {}; using the first. "
                                + "Either mark the definition multi_valued or remove the extra values",
                        name,
                        values.size(),
                        scope.code(),
                        entityId);
            }
            attributes.put(name, values.get(0));
        });
        return attributes;
    }

    /**
     * Flattens one stored value into the values it contributes. A multi-valued attribute is held
     * as one row per value, but a single row may itself hold a JSON array; both conventions are
     * accepted, and neither produces a nested array.
     */
    private List<Object> flatten(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of(value);
        }
        return list.stream()
                .filter(Objects::nonNull)
                .map(element -> (Object) element)
                .toList();
    }

    /**
     * Parses a stored JSONB value back into its natural Java type, so numbers, booleans and
     * arrays survive into the PDP input instead of being flattened to text.
     */
    private Object readValue(String rawJson) {
        if (rawJson == null) {
            return null;
        }
        try {
            return objectMapper.readValue(rawJson, Object.class);
        } catch (JsonProcessingException e) {
            return rawJson;
        }
    }

    private PolicyAttributeDTO toDto(PolicyAttributeValue attributeValue) {
        PolicyAttributeDefinition definition =
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
