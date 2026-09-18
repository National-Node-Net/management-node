/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Which rule actually answered a decision, and how it was chosen. Carried on every decision so an
 * audit trail records the policy that decided rather than merely that one did - without it a
 * routing change or a fallback is invisible after the fact.
 *
 * @param id the rule that answered, e.g. {@code product.subscribe}, or {@code none}
 * @param version the version that rule declares, or {@code none}
 * @param resolution how the rule was selected: {@code route}, {@code exact},
 *     {@code resource_fallback}, {@code global_fallback}, or {@code none} when nothing answered
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicyProvenance(
        @JsonProperty("id") String id,
        @JsonProperty("version") String version,
        @JsonProperty("resolution") String resolution) {

    public static final String NONE_VALUE = "none";

    /** Provenance of a decision no rule answered: switched off, unreachable, or unreadable. */
    public static final PolicyProvenance NONE = new PolicyProvenance(NONE_VALUE, NONE_VALUE, NONE_VALUE);

    public PolicyProvenance {
        id = id == null ? NONE_VALUE : id;
        version = version == null ? NONE_VALUE : version;
        resolution = resolution == null ? NONE_VALUE : resolution;
    }
}
