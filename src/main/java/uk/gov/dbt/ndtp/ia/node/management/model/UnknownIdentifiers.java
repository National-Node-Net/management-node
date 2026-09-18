/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model;

/**
 * Placeholders substituted when an identifier the caller should have supplied is absent, so a
 * value is always present and no caller has to null-check one.
 *
 * <p>They are held here rather than restated wherever they are needed, because a sentinel that is
 * declared in one class and compared in another is coupled by its literal value alone: change one
 * copy and the comparison silently stops matching.
 *
 * <p>A sentinel is never a real identifier, and must not be treated as one. {@code PolicyInputFactory}
 * drops {@link #UNKNOWN_ORG} rather than forwarding it to the PDP, so no policy can come to depend
 * on a magic string that only means "the claim was missing".
 *
 * <p>Follow {@code UNKNOWN_<THING>} when a further one is needed - {@code UNKNOWN_PRODUCT} for a
 * product that cannot be resolved, and so on.
 */
public final class UnknownIdentifiers {

    /** Organisation key used when the token carries no {@code organisation} claim. */
    public static final String UNKNOWN_ORG = "UNKNOWN_ORG";

    /** Client id used when neither the token nor the principal names the OAuth client. */
    public static final String UNKNOWN_CLIENT = "UNKNOWN_CLIENT";

    private UnknownIdentifiers() {}
}
