/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.List;
import lombok.*;

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
