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
 * A request to subscribe the caller's organisation to a product. Mirrors the columns of a
 * {@code product_consumer} grant, so an accepted request maps onto one row.
 *
 * <p>The whole body reaches the PDP as {@code input.request.body}, so the subscription policy can
 * judge the terms being asked for - a schedule type the policy does not permit is refused before
 * the handler runs.
 *
 * @param productId the product to subscribe to
 * @param scheduleType how deliveries are scheduled, e.g. {@code cron} or {@code interval}
 * @param scheduleExpression the expression for that schedule type
 * @param destination where deliveries are sent
 */
@Builder
public record ProductSubscriptionRequestDTO(
        @NotNull @Schema(description = "Product to subscribe to", example = "1") Long productId,
        @Size(max = 100) @Schema(description = "Schedule type", example = "cron") String scheduleType,
        @Size(max = 255) @Schema(description = "Schedule expression", example = "*/5 * * * *")
                String scheduleExpression,
        @Size(max = 500) @Schema(description = "Delivery destination", example = "destination-uri")
                String destination) {}
