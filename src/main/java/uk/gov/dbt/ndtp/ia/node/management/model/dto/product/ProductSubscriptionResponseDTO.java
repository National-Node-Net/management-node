/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto.product;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

/**
 * The outcome of a subscription request: what was granted, to which consumer, and on what terms.
 *
 * <p>The terms are reported rather than merely applied, so a caller can see the validity it was
 * given and the schedule types it may use without asking again.
 *
 * <p>There is deliberately no status. A grant is recorded or the request fails, and once recorded
 * it is in force: {@code product_consumer} has no status column and the configuration API does not
 * filter on one, so a reported status could only ever have been advisory text that nothing read
 * back. Reporting one would imply an approval state the system does not hold.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductSubscriptionResponseDTO(
        @Schema(description = "The grant, once recorded") Long subscriptionId,
        @Schema(description = "Product subscribed") Long productId,
        @Schema(description = "Consumer the subscription was made for") Long consumerId,
        @Schema(description = "Name of that consumer") String consumerName,
        @Schema(description = "When the grant was made") LocalDateTime grantedAt,
        @Schema(description = "Days the organisation may hold the product, as policy decided") Integer validityDays,
        @Schema(description = "Ceiling the caller's purpose allows") Integer maxValidityDays,
        @Schema(description = "Schedule type recorded") String scheduleType,
        @Schema(description = "Schedule expression recorded") String scheduleExpression,
        @Schema(description = "Schedule types policy permits") List<String> permittedScheduleTypes) {}
