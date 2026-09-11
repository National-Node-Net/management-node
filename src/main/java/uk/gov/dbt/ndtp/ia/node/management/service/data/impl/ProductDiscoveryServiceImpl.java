/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ProductDiscoveryResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductDiscoveryService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionClient;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInput;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyRequester;

/**
 * Reuses {@link PolicyDecisionClient} (built for the whole-request PEP on
 * {@code /api/v1/configuration/**}) once per candidate product, since discovery needs to
 * authorise a set of resources rather than the single request URI. The {@code resource} and
 * {@code action} fields of {@link PolicyInput} are repurposed here: {@code resource} carries
 * a stable {@code PRODUCT_RESOURCE_PREFIX + id} identifier instead of a request URI, and
 * {@code action} is the literal string {@code "discover"} instead of an HTTP method.
 */
@Service
@Slf4j
public class ProductDiscoveryServiceImpl implements ProductDiscoveryService {

    private static final String DISCOVER_ACTION = "discover";
    private static final String PRODUCT_RESOURCE_PREFIX = "product:";

    private final ProductService productService;
    private final PolicyDecisionClient policyDecisionClient;

    public ProductDiscoveryServiceImpl(ProductService productService, PolicyDecisionClient policyDecisionClient) {
        this.productService = productService;
        this.policyDecisionClient = policyDecisionClient;
    }

    @Override
    public ProductDiscoveryResponseDTO discover(PolicyRequester requester, String name, String topic, String type) {
        List<ProductDTO> candidates = productService.findDiscoveryCandidates(name, topic, type);
        List<ProductDTO> authorised = filterAuthorised(requester, candidates);
        return ProductDiscoveryResponseDTO.builder().products(authorised).build();
    }

    @Override
    public List<ProductDTO> filterAuthorised(PolicyRequester requester, List<ProductDTO> candidates) {
        return candidates.stream()
                .filter(candidate -> isAuthorised(requester, candidate))
                .toList();
    }

    private boolean isAuthorised(PolicyRequester requester, ProductDTO candidate) {
        PolicyInput input = PolicyInput.of(requester, PRODUCT_RESOURCE_PREFIX + candidate.getId(), DISCOVER_ACTION);
        PolicyDecision decision = policyDecisionClient.evaluate(input);
        if (decision == PolicyDecision.DENY) {
            log.debug(
                    "Policy decision DENY clientId={} resource={} action={}",
                    requester.clientId(),
                    input.resource(),
                    DISCOVER_ACTION);
        }
        return decision == PolicyDecision.ALLOW;
    }
}
