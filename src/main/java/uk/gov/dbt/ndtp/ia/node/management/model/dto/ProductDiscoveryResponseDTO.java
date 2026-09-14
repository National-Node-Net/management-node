/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;

/**
 * Response for {@code POST /v1/product/discovery}: the products the requester is authorised
 * to discover, after policy filtering and search criteria are both applied. Empty (never
 * null) when no products are authorised or none match the search criteria.
 */
@Builder
public record ProductDiscoveryResponseDTO(List<ProductDTO> products) {
    public ProductDiscoveryResponseDTO {
        products = products != null ? products : new ArrayList<>();
    }
}
