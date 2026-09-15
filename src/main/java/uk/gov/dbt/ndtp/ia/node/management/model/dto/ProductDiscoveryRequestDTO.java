/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Builder;

/**
 * Search criteria for {@code POST /v1/product/discover}: a free-text term and a set of named
 * filters.
 *
 * <pre>
 * {
 *   "text": "this is sample text",
 *   "filters": { "key 1": "value", "key 2": [1234, 33, 222] }
 * }
 * </pre>
 *
 * <p>{@code filters} is deliberately untyped. Its keys are not fixed by this DTO, so a caller can
 * send a fact the API does not itself know about and a policy can still read it at
 * {@code input.request.body.filters}; a value may be a scalar or a list. Both fields are optional,
 * and an absent one means "no filter" on that dimension.
 *
 * <p>Criteria only ever narrow the set of products the requester is already authorised to
 * discover - they cannot widen it.
 *
 * <p>{@code filters} is never null and never mutable: an omitted map reads as empty, so callers
 * of this DTO need no null check, and the map cannot be changed after binding.
 *
 * <p>The sizes bounded here are the ones a caller controls: an over-long term, an unbounded
 * number of filters, or an over-long filter name are rejected as a 400 rather than forwarded to
 * the PDP as part of {@code input.request.body}.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductDiscoveryRequestDTO(
        @Size(max = 255) @Schema(description = "Free-text search term", example = "planning") String text,
        @Size(max = 50)
                @Schema(
                        description = "Named filters; values may be a scalar or a list",
                        example = "{\"classification\": \"OFFICIAL\"}")
                Map<@Size(max = 150) String, Object> filters) {

    public ProductDiscoveryRequestDTO {
        filters = filters == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(filters));
    }
}
