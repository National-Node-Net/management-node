/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A policy attribute resolved from the {@code policy_attribute_scope}/{@code policy_attribute_definition}/
 * {@code policy_attribute_value} schema (added by PR #69) - the same three fields as {@link
 * AttributesDTO} (the legacy {@code product_consumer_attribute}-backed representation), so it
 * reads as a drop-in "policy" counterpart rather than a new shape to learn.
 */
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PolicyAttributeDTO {

    /** The attribute's dotted {@code namespace.name} logical identifier (e.g. {@code "policy.risk-tier"}). */
    private String name;

    private String value;
    private String type;
}
