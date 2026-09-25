/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data;

import java.util.Optional;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.DiscoveredProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductViewPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;

/**
 * Reads one product, as much of it as policy allows.
 *
 * <p>A view is a search that returns one product, and is implemented as exactly that: the decision's
 * row filter says which products exist for this caller, and the requested id narrows that to one.
 * So a product the caller could not discover is a product they cannot view, by construction rather
 * than by a second rule that might disagree.
 */
public interface ProductViewService {

    /**
     * Reads the product with the given id.
     *
     * @param productId the product asked for; compared exactly
     * @param decision the {@code product.view} decision taken for this request, or empty when policy
     *     enforcement is switched off - in which case nothing is restricted or withheld
     * @return the product, or empty when there is no such product <em>for this caller</em> - either
     *     it does not exist or the row filter excludes it. The two are deliberately
     *     indistinguishable, so an id cannot be used to probe what exists
     * @throws uk.gov.dbt.ndtp.ia.node.management.exception.AccessRejectedException when the contract
     *     cannot be honoured ({@code 403})
     */
    Optional<DiscoveredProductDTO> view(
            Long productId, Optional<PolicyDecision<ProductViewPolicyDecisionDetails>> decision);
}
