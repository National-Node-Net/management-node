/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.model.dto.product;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Builder;

/**
 * Search criteria for {@code POST /v1/product/discover}. Every member is optional; an empty
 * request returns the first page of everything the caller may discover.
 *
 * <pre>
 * {
 *   "text": "flood",
 *   "filters": [
 *     { "field": "type", "values": ["topic"] },
 *     { "attribute": "record_unit", "operator": "in", "values": ["property", "geographic_area"] }
 *   ],
 *   "sort": [{ "field": "name", "direction": "asc" }],
 *   "page": 0,
 *   "size": 20
 * }
 * </pre>
 *
 * <p>Criteria only ever narrow what the caller is already authorised to discover: filters are
 * AND-ed with each other and with the policy's own row filter.
 *
 * <p>The sizes bounded here are the ones a caller controls, so an over-long term or an unbounded
 * number of filters is a 400 rather than something forwarded to the PDP and the database.
 *
 * @param text free-text term, matched as a case-insensitive substring of the product name or
 *     description
 * @param filters comparisons on product fields and policy attributes, all of which must hold
 * @param sort sort keys, most significant first; by name when omitted
 * @param page zero-based page number
 * @param size page size; clamped to the maximum the policy allows the caller
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductDiscoveryRequestDTO(
        @Size(max = 255) @Schema(description = "Free-text term", example = "flood") String text,
        @Valid @Size(max = 50) List<ProductDiscoveryFilterDTO> filters,
        @Valid @Size(max = 3) List<ProductDiscoverySortDTO> sort,
        @Min(0) @Schema(defaultValue = "0") Integer page,
        @Min(1) @Schema(defaultValue = "20") Integer size) {

    /** The request a caller makes by sending no body at all. */
    public static final ProductDiscoveryRequestDTO EMPTY = new ProductDiscoveryRequestDTO(null, null, null, null, null);

    public ProductDiscoveryRequestDTO {
        filters = filters == null ? List.of() : List.copyOf(filters);
        sort = sort == null ? List.of() : List.copyOf(sort);
    }
}
