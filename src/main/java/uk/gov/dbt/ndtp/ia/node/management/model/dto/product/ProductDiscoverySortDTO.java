/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.model.dto.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;

/**
 * One sort key of a discovery search. Exactly one of {@code field} and {@code attribute} is given.
 *
 * @param scope the entity the name belongs to; the product when omitted
 * @param field a product field name
 * @param attribute a policy attribute name
 * @param direction {@code asc} (the default) or {@code desc}
 */
@Builder
public record ProductDiscoverySortDTO(
        FilterScope scope,
        @Size(max = 150) @Schema(example = "name") String field,
        @Size(max = 150) String attribute,
        @Pattern(regexp = "(?i)asc|desc", message = "must be 'asc' or 'desc'") @Schema(defaultValue = "asc")
                String direction) {

    public boolean descending() {
        return "desc".equalsIgnoreCase(direction);
    }
}
