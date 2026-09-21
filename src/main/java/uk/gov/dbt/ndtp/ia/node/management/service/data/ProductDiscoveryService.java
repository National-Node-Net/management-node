/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data;

import java.util.Optional;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;

/**
 * Searches the data products a caller may discover.
 *
 * <p>Policy, not this service, decides what a caller can find. The decision taken for the request
 * carries a search contract - which products exist for this caller, what they may filter on, and
 * what is withheld - and the search turns that contract, together with the caller's criteria, into
 * one database query. There is no entitlement logic here and no policy call of its own: the
 * decision the endpoint was given is the only one taken.
 */
public interface ProductDiscoveryService {

    /**
     * Runs one search.
     *
     * @param criteria what the caller asked for; null when they sent no body, which searches
     *     everything they may discover
     * @param decision the {@code product.discover} decision taken for this request, or empty when
     *     policy enforcement is switched off - in which case nothing is restricted or withheld and
     *     the response carries no policy block
     * @return the page of products, where the page sits in the whole result, and (when policy
     *     decided) what the caller was allowed
     * @throws uk.gov.dbt.ndtp.ia.node.management.exception.InvalidSearchCriteriaException when the
     *     criteria are malformed ({@code 400})
     * @throws uk.gov.dbt.ndtp.ia.node.management.exception.AccessRejectedException when the criteria
     *     ask for something policy does not permit, or the contract cannot be honoured ({@code 403})
     */
    ProductDiscoveryResponseDTO discover(
            ProductDiscoveryRequestDTO criteria,
            Optional<PolicyDecision<ProductDiscoveryPolicyDecisionDetails>> decision);
}
