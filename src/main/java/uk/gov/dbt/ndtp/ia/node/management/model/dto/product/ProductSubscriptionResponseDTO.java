/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto.product;

import java.util.List;
import lombok.Builder;

/**
 * The outcome of a subscription request, shaped by the terms the subscription policy set.
 *
 * @param productId the product subscribed to
 * @param status {@code ACCEPTED}, or {@code PENDING_APPROVAL} when the policy requires approval
 * @param maxValidityDays the longest validity the policy allows for the grant
 * @param permittedScheduleTypes the schedule types the policy allows
 */
@Builder
public record ProductSubscriptionResponseDTO(
        Long productId, String status, Integer maxValidityDays, List<String> permittedScheduleTypes) {

    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";

    public ProductSubscriptionResponseDTO {
        permittedScheduleTypes = permittedScheduleTypes == null ? List.of() : List.copyOf(permittedScheduleTypes);
    }
}
