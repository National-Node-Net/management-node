/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The {@code input} document of a PDP (OPA) decision request.
 *
 * <p>The shape is deliberately an object graph rather than a flat set of strings, so a policy
 * can reason about the caller, the organisation, the target entity and the HTTP call
 * independently, and so new facts can be added to any one of them without changing the others:
 *
 * <pre>
 * {
 *   "input": {
 *     "subject":  { "kind", "user_id", "token", "organisation": { "organisation_key", "attributes" } },
 *     "action":   "discover",
 *     "resource": { "kind", "id", "attributes" },
 *     "request":  { "headers", "query", "path", "method", "body" }
 *   }
 * }
 * </pre>
 *
 * <p>Instances are assembled by {@link PolicyInputFactory}, which is the only place that knows
 * how each field is sourced (token claims, client certificate, database attributes, servlet
 * request). {@code action} and {@code resource.kind} are derived from the request path: the
 * final segment is the action and the one before it is the resource kind, so
 * {@code /api/v1/product/discover} gives action {@code discover} on kind {@code product}.
 *
 * @param subject who is asking, and for which organisation
 * @param action the operation being attempted
 * @param resource what is being acted on
 * @param request the HTTP call that triggered the decision
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyInput(PolicySubject subject, String action, PolicyResource resource, PolicyHttpRequest request) {

    /**
     * Returns a copy of this input targeting a specific entity of the same resource kind,
     * leaving subject, action and request untouched. Used by per-candidate evaluation, where
     * one request produces many decisions that differ only by the entity under test.
     *
     * @param id the entity's id
     * @param attributes that entity's policy attributes
     * @return a new input for that entity
     */
    public PolicyInput withResource(String id, java.util.Map<String, Object> attributes) {
        return new PolicyInput(subject, action, new PolicyResource(resource.kind(), id, attributes), request);
    }
}
