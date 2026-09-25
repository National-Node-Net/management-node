/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.model.dto.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Builder;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.ComparisonOperator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;

/**
 * One filter of a discovery search: a comparison of a product <b>field</b> (a column, such as
 * {@code type}) or a policy <b>attribute</b> (such as {@code record_unit}) with some values.
 * Exactly one of {@code field} and {@code attribute} is given.
 *
 * <pre>
 * { "field": "type", "values": ["topic"] }
 * { "attribute": "record_unit", "operator": "in", "values": ["property", "household"] }
 * { "scope": "organisation", "field": "key", "values": ["ENV"] }
 * </pre>
 *
 * @param scope the entity the name belongs to; the product when omitted
 * @param field a product field name
 * @param attribute a policy attribute name
 * @param operator how to compare; when omitted, chosen from the target and the number of values
 * @param values the values to compare with
 */
@Builder
public record ProductDiscoveryFilterDTO(
        @Schema(description = "Entity the name belongs to", defaultValue = "product") FilterScope scope,
        @Size(max = 150) @Schema(description = "Product field to filter on", example = "type") String field,
        @Size(max = 150) @Schema(description = "Policy attribute to filter on", example = "record_unit")
                String attribute,
        @Schema(description = "Comparison; defaults to contains, eq or in depending on the target and values")
                ComparisonOperator operator,
        @Size(max = 50) @Schema(description = "Values to compare with") List<Object> values) {

    public ProductDiscoveryFilterDTO {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
