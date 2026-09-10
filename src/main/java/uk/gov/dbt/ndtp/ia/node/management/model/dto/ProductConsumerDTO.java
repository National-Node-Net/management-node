/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO for ConsumerAllowedDataProvider entity.
 */
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductConsumerDTO {
    /** The underlying {@code product_consumer.id} - not exposed to callers, only needed
     * internally to key {@code SUBSCRIPTION}-scope policy attribute lookups. */
    @JsonIgnore
    private Long id;

    @JsonIgnore
    private Long productId;

    @JsonIgnore
    private Long consumerId;

    @JsonIgnore
    private Timestamp grantedTs;

    @JsonIgnore
    private BigDecimal validity;

    private String scheduleType;
    private String scheduleExpression;
    private String destination;
    private final List<AttributesDTO> attributes = new ArrayList<>();
    private final List<PolicyAttributeDTO> policyAttributes = new ArrayList<>();
}
