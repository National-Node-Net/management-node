/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.DiscoveredProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.ComparisonOperator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductViewPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductViewService;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.DiscoveredProductAssembler;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.DiscoveryObligations;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductField;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductProjection;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductQueryPlanner;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchContract;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchCriteria;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchQuery;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;

/**
 * Reads one product as a search constrained to it.
 *
 * <p>There is deliberately no product-loading code here. The decision's row filter becomes the
 * {@code WHERE} clause exactly as it does for a search, the requested id is one more condition
 * AND-ed onto it, and the same assembler builds the response with the same masking. What the caller
 * may see is therefore decided in one place for both endpoints, and an id cannot reach a product a
 * search would have withheld.
 *
 * <p>The page is one row. No count is run - the caller asked for a product, not for how many match.
 */
@Service
@Slf4j
public class ProductViewServiceImpl implements ProductViewService {

    /** Names this endpoint in the planner's refusal and audit lines. */
    private static final String WHAT = "Product view";

    /**
     * Reading one product returns the <b>whole</b> object model - the owning organisation, the
     * producer, the consumers and their subscriptions, who subscribes, and every entity's policy
     * attributes - as far as policy permits. This is what a search deliberately does not carry.
     */
    private static final ProductProjection PROJECTION = ProductProjection.FULL;

    private final ProductQueryPlanner planner;
    private final ProductDiscoveryRepository repository;
    private final DiscoveredProductAssembler assembler;

    public ProductViewServiceImpl(
            ProductQueryPlanner planner, ProductDiscoveryRepository repository, DiscoveredProductAssembler assembler) {
        this.planner = planner;
        this.repository = repository;
        this.assembler = assembler;
    }

    @Override
    public Optional<DiscoveredProductDTO> view(
            Long productId, Optional<PolicyDecision<ProductViewPolicyDecisionDetails>> decision) {

        ProductSearchContract contract = planner.contractFor(WHAT, decision);
        ProductSearchQuery query = planner.compile(WHAT, contract, criteriaFor(productId), PROJECTION, decision);

        List<Map<String, Object>> rows = repository.findPage(query);
        // No row means no such product *for this caller* - either it does not exist or the row
        // filter excluded it. Nothing more is loaded: the blocks exist to describe a product that
        // was found.
        Optional<DiscoveredProductDTO> product = rows.isEmpty()
                ? Optional.empty()
                : assembler.assemble(rows, contract, PROJECTION).stream().findFirst();

        audit(productId, contract, product, decision);
        return product;
    }

    /**
     * The whole of what this endpoint asks for: one product, matched exactly.
     *
     * <p>The id is an ordinary field comparison, so it compiles, binds and combines like any other
     * - it is AND-ed with the policy's row filter and can only ever narrow it. Nothing here is
     * caller-supplied criteria: there is no text, no filter list and no sort to validate, and the
     * page is a single row.
     */
    private static ProductSearchCriteria criteriaFor(Long productId) {
        FilterNode.Comparison byId =
                FilterNode.Comparison.ofField(ProductField.ID.apiName(), ComparisonOperator.EQ, productId);
        return new ProductSearchCriteria(null, List.of(byId), List.of(), 0, 1);
    }

    /**
     * The audit record the {@code audit_access} obligation asks for. It records which product was
     * asked for and whether anything came back, so a refusal by row filter is distinguishable in the
     * log from a product that does not exist - a distinction the response deliberately does not make.
     */
    private void audit(
            Long productId,
            ProductSearchContract contract,
            Optional<DiscoveredProductDTO> product,
            Optional<PolicyDecision<ProductViewPolicyDecisionDetails>> decision) {
        if (!contract.obligations().contains(DiscoveryObligations.AUDIT_ACCESS)) {
            return;
        }
        log.info(
                "Product view audit policy={} productId={} found={} accessLevel={}",
                ProductQueryPlanner.provenance(decision),
                productId,
                product.isPresent(),
                decision.map(PolicyDecision::details)
                        .map(ProductViewPolicyDecisionDetails::accessLevel)
                        .orElse(null));
    }
}
