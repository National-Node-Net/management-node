/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import lombok.*;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.policy.PolicyAttributeDTO;

/**
 * DTO for OrganisationDataProvider entity.
 */
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductDTO {

    @JsonIgnore
    private Long id;

    @JsonIgnore
    private Long producerId;

    private String name;

    private String topic;

    /** Prose describing the product; what discovery's free-text search reads. */
    @Size(max = 4000)
    private String description;

    private String type;

    private String source;

    // @Builder.Default on each list: without it the generated builder bypasses these initialisers
    // and hands back nulls, which is why callers used to have to null-check getConsumers().

    @Builder.Default
    private List<ConsumerDTO> consumers = new ArrayList<>();

    @Builder.Default
    private List<ProductConsumerDTO> configurations = new ArrayList<>();

    /** Live {@code PRODUCT}-scope policy attributes for this product. */
    @Builder.Default
    private List<PolicyAttributeDTO> policyAttributes = new ArrayList<>();
}
