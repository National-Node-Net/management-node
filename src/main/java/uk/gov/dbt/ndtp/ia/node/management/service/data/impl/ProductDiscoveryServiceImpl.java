/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeScopeCode;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductDiscoveryService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionClient;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInput;

/**
 * Reuses {@link PolicyDecisionClient} (built for the whole-request PEP on
 * {@code /api/v1/configuration/**}) once per candidate product, since discover authorises a set
 * of resources rather than a single request. The caller supplies a {@link PolicyInput} carrying
 * the subject, action and request facts for the call; this service varies only
 * {@code resource.id} and {@code resource.attributes} per candidate.
 *
 * <p>The caller also supplies the decision already taken for the request as a whole. Each
 * per-candidate decision is narrowed by it, so a product survives only if both permit it and the
 * attribute lists a caller acts on are the two merged.
 */
@Service
@Slf4j
public class ProductDiscoveryServiceImpl implements ProductDiscoveryService {

    private final ProductService productService;
    private final PolicyDecisionClient policyDecisionClient;
    private final PolicyAttributeService policyAttributeService;

    public ProductDiscoveryServiceImpl(
            ProductService productService,
            PolicyDecisionClient policyDecisionClient,
            PolicyAttributeService policyAttributeService) {
        this.productService = productService;
        this.policyDecisionClient = policyDecisionClient;
        this.policyAttributeService = policyAttributeService;
    }

    @Override
    public ProductDiscoveryResponseDTO discover(
            PolicyInput input,
            PolicyDecision<ProductDiscoveryPolicyDecisionDetails> requestDecision,
            String name,
            String topic,
            String type) {
        List<ProductDTO> candidates = productService.findDiscoveryCandidates(name, topic, type);
        List<ProductDTO> authorised = filterAuthorised(input, requestDecision, candidates);
        return ProductDiscoveryResponseDTO.builder().products(authorised).build();
    }

    @Override
    public List<ProductDTO> filterAuthorised(
            PolicyInput input,
            PolicyDecision<ProductDiscoveryPolicyDecisionDetails> requestDecision,
            List<ProductDTO> candidates) {
        return candidates.stream()
                .filter(candidate -> decide(input, requestDecision, candidate).allow())
                .toList();
    }

    /**
     * Evaluates one decision per candidate. Only the resource changes between candidates - the
     * subject, action and request facts are built once by the caller and reused, so a candidate
     * differs from its neighbours only by the entity under test and that entity's attributes.
     */
    private PolicyDecision<ProductDiscoveryPolicyDecisionDetails> decide(
            PolicyInput input,
            PolicyDecision<ProductDiscoveryPolicyDecisionDetails> requestDecision,
            ProductDTO candidate) {
        String productId = String.valueOf(candidate.getId());
        PolicyInput candidateInput = input.withResource(
                productId,
                policyAttributeService.findAttributeMap(candidate.getId(), PolicyAttributeScopeCode.PRODUCT));
        PolicyDecision<ProductDiscoveryPolicyDecisionDetails> decision =
                policyDecisionClient.evaluate(candidateInput, ProductDiscoveryPolicyDecisionDetails.class);
        PolicyDecision<ProductDiscoveryPolicyDecisionDetails> effective =
                requestDecision == null ? decision : requestDecision.combinedWith(decision);
        if (!effective.allow()) {
            log.debug(
                    "Policy decision DENY subject={} resource={}:{} action={}",
                    candidateInput.subject() == null
                            ? null
                            : candidateInput.subject().userId(),
                    candidateInput.resource().kind(),
                    productId,
                    candidateInput.action());
        }
        return effective;
    }
}
