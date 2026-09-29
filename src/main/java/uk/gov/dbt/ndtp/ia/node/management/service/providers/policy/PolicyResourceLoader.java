/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import java.util.Optional;

/**
 * Reads the entity a decision is about, once something has established which entity that is.
 *
 * <p>Finding the id and reading the entity are separate jobs, and separating them is what makes
 * the mechanism consistent. {@link PolicyResourceIdExtractor} finds the id by one convention for
 * every endpoint and every transport; a loader here knows how to read one <em>kind</em> of thing
 * and nothing about how the request was shaped.
 *
 * <p>Adding a kind is therefore a single class: implement this for {@code consumer}, and every
 * endpoint that names a {@code consumerId} in a path or a body can have its consumer sent to the
 * PDP without touching the extractor, the factory or the enforcement point.
 *
 * <p>A loader is only consulted for an endpoint that declares {@code @Policy(loadResource = true)},
 * so no endpoint pays for a read whose result no rule uses.
 */
public interface PolicyResourceLoader {

    /** Whether this loader knows how to read entities of {@code resourceKind}. */
    boolean supports(String resourceKind);

    /**
     * The entity with this id, carrying its own columns as fields and its live policy attributes.
     *
     * @param resourceKind the kind being decided, already matched by {@link #supports}
     * @param id the entity id as the request carried it, never null or blank
     * @return the loaded entity, or empty when the id is malformed or matches nothing. Empty rather
     *     than an error: whether an unknown id is a refusal is for the rule and the handler to
     *     decide, and failing the decision here would report a missing entity as a {@code 403}.
     */
    Optional<PolicyResource> load(String resourceKind, String id);
}
