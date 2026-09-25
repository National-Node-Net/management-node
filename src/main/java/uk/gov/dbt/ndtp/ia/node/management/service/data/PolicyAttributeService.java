/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data;

import java.util.List;
import java.util.Map;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.policy.PolicyAttributeDTO;

/**
 * Resolves every live policy attribute recorded against one entity within one {@link
 * PolicyAttributeScopeCode}.
 */
public interface PolicyAttributeService {

    /**
     * @param entityId the polymorphic entity id (e.g. a {@code producer.id} or {@code
     *     consumer.id})
     * @param scope which {@code policy_attribute_scope} to resolve attributes for
     * @return the entity's live policy attributes for that scope, or an empty list (never
     *     {@code null}) if it has none
     */
    List<PolicyAttributeDTO> findAttributes(Long entityId, PolicyAttributeScopeCode scope);

    /**
     * The same live attribute values as {@link #findAttributes}, keyed by attribute name with
     * their JSON type preserved, for embedding in a PDP decision input.
     *
     * <p>Unlike {@link #findAttributes}, which renders every value as a {@code String}, this keeps
     * numbers, booleans and arrays intact rather than collapsing them to an empty string.
     *
     * <p>A {@code multi_valued} attribute is held as one {@code policy_attribute_value} row per
     * value, so its values are collected into a list. The shape follows the <em>definition</em>,
     * not the data: a multi-valued attribute is always a list, even when only one value is
     * recorded, so a policy can index it without first checking how many values happen to exist.
     * A single-valued attribute is always a scalar; if it somehow holds several live values, the
     * first is used and the anomaly logged.
     *
     * <p>Attribute names are unique per entity in practice; if two namespaces define the same name
     * for one entity, the first definition wins and the clash is logged rather than silently
     * overwriting.
     *
     * @param entityId id of the row the attributes are attached to
     * @param scope which entity type {@code entityId} refers to
     * @return attribute name to JSON-typed value - a list for multi-valued attributes, a scalar
     *     otherwise. Never null and never containing null values
     */
    Map<String, Object> findAttributeMap(Long entityId, PolicyAttributeScopeCode scope);
}
