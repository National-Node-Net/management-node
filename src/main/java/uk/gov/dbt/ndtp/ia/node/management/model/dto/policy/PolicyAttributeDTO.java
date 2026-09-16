/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto.policy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A policy attribute resolved from the {@code policy_attribute_scope}/{@code policy_attribute_definition}/
 * {@code policy_attribute_value} schema.
 *
 * <p>{@code namespace} is carried as its own field rather than folded into {@code name} as a
 * dotted prefix: consumers of this payload (policy rules, in particular) match on the namespace
 * and the name separately, and splitting a dotted string back apart is both needless work and
 * ambiguous once a name itself contains a dot.
 */
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PolicyAttributeDTO {

    /** The namespace the attribute is defined in (e.g. {@code "policy"}). */
    private String namespace;

    /** The attribute's name within its namespace (e.g. {@code "risk-tier"}). */
    private String name;

    private String value;
}
