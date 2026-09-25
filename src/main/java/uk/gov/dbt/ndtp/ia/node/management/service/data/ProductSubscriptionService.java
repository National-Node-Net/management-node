/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data;

import java.util.Optional;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductSubscriptionRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductSubscriptionResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductSubscriptionPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;

/**
 * Records a subscription of an organisation to a product.
 *
 * <p>This is a service rather than controller logic because making the grant is not a single
 * write: the consumer has to be chosen, the request checked against what already exists, and the
 * validity taken from the policy decision. The decision is passed in rather than fetched, so the
 * terms applied are the ones the enforcement point already obtained for this request and cannot
 * differ from the ones it was allowed on.
 */
public interface ProductSubscriptionService {

    /**
     * Subscribes the caller's organisation to a product.
     *
     * @param request the product, optionally the consumer, and the schedule
     * @param organisationKey the calling organisation, as its token carries it
     * @param decision the subscription decision, empty when policy is switched off
     * @return the grant that was recorded, with the terms policy set
     * @throws uk.gov.dbt.ndtp.ia.node.management.exception.SubscriptionRejectedException when no
     *     consumer can be chosen, the product does not exist, or the subscription already exists
     */
    ProductSubscriptionResponseDTO subscribe(
            ProductSubscriptionRequestDTO request,
            String organisationKey,
            Optional<PolicyDecision<ProductSubscriptionPolicyDecisionDetails>> decision);
}
