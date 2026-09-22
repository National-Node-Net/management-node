/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import java.util.Objects;

/**
 * What a decision is about and how its answer is read: the resource kind and action that select a
 * rule at the PDP, and the type that rule's {@code details} object is read into.
 *
 * <p>Built from a {@code @Policy} annotation by the enforcement point, or directly by a caller that
 * asks for decisions itself, such as per-candidate evaluation. Kept free of any web type so the
 * service layer can use it without depending on the annotation.
 *
 * @param resource the resource kind, e.g. {@code product}
 * @param action the action on that resource, e.g. {@code subscribe}
 * @param <D> the type the decision's details are read into
 * @param detailsType the type the decision's details are read into; the generic
 *     {@link PolicyDecisionDetails} when the caller needs no typed details
 */
public record PolicyTarget<D extends PolicyDecisionDetails>(
        String resource, String action, Class<D> detailsType, boolean loadResource) {

    /** A target that decides about a kind of thing, reading no entity. */
    public PolicyTarget(String resource, String action, Class<D> detailsType) {
        this(resource, action, detailsType, false);
    }

    public PolicyTarget {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(detailsType, "detailsType");
    }

    /** A target whose details are left in the generic {@link PolicyDecisionDetails} form. */
    public static PolicyTarget<PolicyDecisionDetails> of(String resource, String action) {
        return new PolicyTarget<>(resource, action, PolicyDecisionDetails.class);
    }
}
