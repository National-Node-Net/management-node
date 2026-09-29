/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.organisation.OrganisationDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.policy.PolicyAttributeDTO;

/**
 * DTO for consumerId entity.
 */
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ConsumerDTO {
    @JsonIgnore
    private Long id;

    private String name;

    @JsonIgnore
    private Long orgId;

    private String idpClientId;

    private String scheduleType;

    private String scheduleExpression;

    private final List<ProductConsumerAttributeDTO> attributes = new ArrayList<>();

    private final List<PolicyAttributeDTO> policyAttributes = new ArrayList<>();

    /**
     * The organisation this consumer belongs to, including its key and policy attributes. Replaces
     * the former flat {@code organisationPolicyAttributes} list, which carried the same attributes
     * with no way to tell which organisation they described.
     */
    private OrganisationDTO organisation;
}
