/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
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
public record PolicyResource(String kind, String id, Map<String, Object> fields, Map<String, Object> attributes) {

    /**
     * A resource of {@code kind} with no specific entity, no fields and no attributes.
     *
     * <p>This is what an endpoint that names no entity sends, which is most of them. Nothing is
     * loaded for such a request: a rule sees the kind it is deciding about and nothing more.
     */
    public static PolicyResource ofKind(String kind) {
        return new PolicyResource(kind, null, Map.of(), Map.of());
    }

    /**
     * A resource of {@code kind} identified by {@code id}, carrying the entity's own columns as
     * {@code fields} and its live policy attributes as {@code attributes}.
     */
    public static PolicyResource of(
            String kind, String id, Map<String, Object> fields, Map<String, Object> attributes) {
        return new PolicyResource(
                kind, id, fields == null ? Map.of() : fields, attributes == null ? Map.of() : attributes);
    }
}
