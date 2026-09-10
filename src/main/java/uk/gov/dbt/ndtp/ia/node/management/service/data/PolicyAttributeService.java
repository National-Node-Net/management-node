/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data;

import java.util.List;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.PolicyAttributeDTO;

/**
 * Resolves every live policy attribute recorded against one entity within one {@link
 * PolicyAttributeScope}.
 */
public interface PolicyAttributeService {

    /**
     * @param entityId the polymorphic entity id (e.g. a {@code producer.id} or {@code
     *     consumer.id})
     * @param scope which {@code policy_attribute_scope} to resolve attributes for
     * @return the entity's live policy attributes for that scope, or an empty list (never
     *     {@code null}) if it has none
     */
    List<PolicyAttributeDTO> findAttributes(Long entityId, PolicyAttributeScope scope);
}
