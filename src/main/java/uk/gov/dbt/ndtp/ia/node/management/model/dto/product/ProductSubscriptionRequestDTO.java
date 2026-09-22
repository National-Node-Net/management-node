/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * A request to subscribe the caller's organisation to a product.
 *
 * <p>Only the product is required. The consumer is optional because most organisations run one,
 * or nominate a default; naming one is for organisations that run several. The schedule fields are
 * optional because a subscription without a stated schedule is still a valid subscription: the
 * service applies {@link #DEFAULT_SCHEDULE_TYPE} and {@link #DEFAULT_SCHEDULE_EXPRESSION}.
 */
@Builder
public record ProductSubscriptionRequestDTO(
        @NotNull @Schema(description = "Product to subscribe to", example = "1") Long productId,
        @Schema(description = "Consumer to subscribe. Omit to use the organisation's default consumer", example = "4")
                Long consumerId,
        @Size(max = 100) @Schema(description = "Schedule type", example = "interval", defaultValue = "interval")
                String scheduleType,
        @Size(max = 255) @Schema(description = "Schedule expression", example = "P1D", defaultValue = "P1D")
                String scheduleExpression,
        @Size(max = 500) @Schema(description = "Delivery destination", example = "destination-uri")
                String destination) {

    /** Applied when the caller states no schedule type. */
    public static final String DEFAULT_SCHEDULE_TYPE = "interval";

    /** Applied when the caller states no schedule expression: daily, as an ISO-8601 duration. */
    public static final String DEFAULT_SCHEDULE_EXPRESSION = "P1D";

    /** The stated schedule type, or the default when none was stated. */
    public String scheduleTypeOrDefault() {
        return scheduleType == null || scheduleType.isBlank() ? DEFAULT_SCHEDULE_TYPE : scheduleType.trim();
    }

    /** The stated schedule expression, or the default when none was stated. */
    public String scheduleExpressionOrDefault() {
        return scheduleExpression == null || scheduleExpression.isBlank()
                ? DEFAULT_SCHEDULE_EXPRESSION
                : scheduleExpression.trim();
    }
}
