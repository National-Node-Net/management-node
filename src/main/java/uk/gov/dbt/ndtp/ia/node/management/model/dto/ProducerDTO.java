/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO for OrganisationProducer entity.
 */
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProducerDTO {
    private final List<ProductDTO> products = new ArrayList<>();

    @JsonIgnore
    private Long id;

    private String name;
    private String description;

    @JsonIgnore
    private Long orgId;

    private Boolean active;
    private String host;
    private BigDecimal port;
    private Boolean tls;
    private String idpClientId;

    /** The organisation this producer belongs to, including its key and policy attributes. */
    private OrganisationDTO organisation;

    private final List<PolicyAttributeDTO> policyAttributes = new ArrayList<>();
}
