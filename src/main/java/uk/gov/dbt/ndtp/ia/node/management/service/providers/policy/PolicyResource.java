/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * What is being acted on, as the PDP sees it.
 *
 * <p>{@code kind} is taken from the path segment preceding the action (so
 * {@code /api/v1/product/discover} yields {@code product}). {@code id} identifies the specific
 * entity when the decision is about one - product discovery evaluates one decision per candidate
 * product and sets the candidate's id here - and is null for whole-endpoint decisions.
 *
 * @param kind the type of entity being acted on
 * @param id the entity's id when the decision concerns a single entity, otherwise null
 * @param attributes scope-appropriate policy attributes for this entity, never null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyResource(String kind, String id, Map<String, Object> attributes) {

    /** A resource of {@code kind} with no specific entity and no attributes. */
    public static PolicyResource ofKind(String kind) {
        return new PolicyResource(kind, null, Map.of());
    }
}
