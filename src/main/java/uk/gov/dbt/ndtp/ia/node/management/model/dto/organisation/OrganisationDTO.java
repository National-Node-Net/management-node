/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto.organisation;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.policy.PolicyAttributeDTO;

/**
 * The organisation a producer or consumer belongs to, as exposed on the configuration APIs.
 *
 * <p>Carries {@code key} rather than the database id: the key is stable, readable, and unique,
 * so a federator can match on it without depending on ids that differ between environments.
 */
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrganisationDTO {

    /** The organisation's display name (e.g. {@code "Organisation A"}). */
    private String name;

    /** The organisation's unique key (e.g. {@code "ORG_A"}). */
    private String key;

    /** Live {@code ORGANISATION}-scope policy attributes for this organisation. */
    private final List<PolicyAttributeDTO> policyAttributes = new ArrayList<>();
}
