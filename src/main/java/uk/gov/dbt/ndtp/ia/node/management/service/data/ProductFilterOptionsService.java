/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data;

import java.util.Optional;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductFilterOptionsResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;

/**
 * Builds the product catalogue's filter panel for {@code POST /api/v1/product/discover/filter}.
 *
 * <p>The panel is assembled from the same front half {@link ProductDiscoveryService} runs: the same
 * policy contract, the same criteria validation and refusals, and the same total the database counts
 * under the same row filter. Nothing here decides anything a search would decide differently - this
 * service only adds, for each filter the contract permits, the distinct values it holds for the
 * caller's narrowed products, so a client can build a search form from data rather than guesswork.
 */
public interface ProductFilterOptionsService {

    /**
     * Builds the panel for one request.
     *
     * @param request what the caller has currently selected, so facet counts reflect it; null when
     *     they sent no body, which starts from every product they may discover
     * @param callerOrganisation the calling organisation's key, from the token; null when it is not
     *     known
     * @param decision the {@code product.discover} decision taken for this request, or empty when
     *     policy enforcement is switched off - in which case nothing is restricted or withheld and
     *     the response carries no policy block
     * @return the baseline total, whether and how free text may be searched, every filter the
     *     caller may apply - with distinct values where listing them is worth it - and (when policy
     *     decided) what the caller was allowed
     * @throws uk.gov.dbt.ndtp.ia.node.management.exception.InvalidSearchCriteriaException when the
     *     criteria are malformed ({@code 400})
     * @throws uk.gov.dbt.ndtp.ia.node.management.exception.AccessRejectedException when the criteria
     *     ask for something policy does not permit, or the contract cannot be honoured ({@code 403})
     */
    ProductFilterOptionsResponseDTO filterOptions(
            ProductDiscoveryRequestDTO request,
            String callerOrganisation,
            Optional<PolicyDecision<ProductDiscoveryPolicyDecisionDetails>> decision);
}
