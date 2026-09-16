/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data;

import java.util.List;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.DefaultPolicyDecisionOutput;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInput;

/**
 * Runs product discover: queries candidate products matching the requester's search
 * criteria, then applies per-candidate PDP authorisation, keeping only the products the
 * requester is authorised to discover.
 */
public interface ProductDiscoveryService {

    /**
     * Queries discover candidates matching the given search criteria, then evaluates one
     * PDP decision per candidate, keeping only the ALLOWed ones.
     *
     * @param input who is asking, as the PDP sees them
     * @param requestDecision the decision already taken for the request as a whole, which each
     *     per-candidate decision is narrowed by. {@link DefaultPolicyDecisionOutput#ALLOW} when the
     *     endpoint took no whole-request decision
     * @param name optional case-insensitive contains filter on product name
     * @param topic optional case-insensitive contains filter on product topic
     * @param type optional case-insensitive exact filter on product type name
     * @return the products the requester is authorised to discover, matching the criteria
     */
    ProductDiscoveryResponseDTO discover(
            PolicyInput input, DefaultPolicyDecisionOutput requestDecision, String name, String topic, String type);

    /**
     * Evaluates one PDP decision per candidate product and returns only the ALLOWed ones. A
     * candidate is excluded (not the whole request failed) if the PDP denies it or the PDP
     * call itself fails, so a partial PDP outage degrades results rather than the request.
     *
     * @param input who is asking, as the PDP sees them
     * @param requestDecision the decision already taken for the request as a whole, which each
     *     per-candidate decision is narrowed by
     * @param candidates discover candidate products to authorise
     * @return the subset of candidates the PDP allows for this requester
     */
    List<ProductDTO> filterAuthorised(
            PolicyInput input, DefaultPolicyDecisionOutput requestDecision, List<ProductDTO> candidates);
}
