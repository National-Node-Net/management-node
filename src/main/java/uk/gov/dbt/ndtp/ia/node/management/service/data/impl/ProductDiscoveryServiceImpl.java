/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.DiscoveredProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductDiscoveryService;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.DiscoveredProductAssembler;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.DiscoveryObligations;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductField;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductProjection;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductQueryPlanner;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchContract;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchCriteria;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchCriteriaFactory;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchQuery;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;

/**
 * Runs a search in four steps, each owned by one collaborator:
 *
 * <ol>
 *   <li><b>plan</b> - what the decision grants, or what the absence of one means, and the statement
 *       that follows from it ({@link ProductQueryPlanner}, shared with the view endpoint);
 *   <li><b>criteria</b> - validate what the caller asked for against the contract
 *       ({@link ProductSearchCriteriaFactory});
 *   <li><b>execute</b> - the page and the total ({@link ProductDiscoveryRepository});
 *   <li><b>assemble</b> - the rows and the blocks the caller may see
 *       ({@link DiscoveredProductAssembler}).
 * </ol>
 *
 * <p>Two properties of that sequence are what make it trustworthy, and neither may be weakened: the
 * policy's conditions are part of the query rather than a filter applied afterwards, so paging and
 * totals are exact and nothing withheld is ever read; and every failure refuses the search instead
 * of widening it.
 */
@Service
@Slf4j
public class ProductDiscoveryServiceImpl implements ProductDiscoveryService {

    /** Names this endpoint in the planner's refusal and audit lines. */
    private static final String WHAT = "Product discover";

    /**
     * A search returns a <b>summary</b> of each product - its id, name, description and type - and
     * nothing else. The rest of the object model is what {@code GET /api/v1/product/{productId}}
     * is for, so a result carries enough to recognise a product and to ask for it by id.
     *
     * <p>This is the only difference between the two endpoints. Policy answers both identically;
     * they differ in how much of that answer each returns. Because the projection is applied to the
     * query rather than to the response, a search does not select the columns it will not return,
     * and runs no block or attribute query at all.
     */
    private static final ProductProjection PROJECTION = ProductProjection.SUMMARY;

    private final ProductQueryPlanner planner;
    private final ProductSearchCriteriaFactory criteriaFactory;
    private final ProductDiscoveryRepository repository;
    private final DiscoveredProductAssembler assembler;

    public ProductDiscoveryServiceImpl(
            ProductQueryPlanner planner,
            ProductSearchCriteriaFactory criteriaFactory,
            ProductDiscoveryRepository repository,
            DiscoveredProductAssembler assembler) {
        this.planner = planner;
        this.criteriaFactory = criteriaFactory;
        this.repository = repository;
        this.assembler = assembler;
    }

    @Override
    public ProductDiscoveryResponseDTO discover(
            ProductDiscoveryRequestDTO request,
            Optional<PolicyDecision<ProductDiscoveryPolicyDecisionDetails>> decision) {

        ProductSearchContract contract = planner.contractFor(WHAT, decision);
        ProductSearchCriteria criteria =
                criteriaFactory.create(request, contract, planner.sensitiveAttributeNames(contract));
        ProductSearchQuery query = planner.compile(WHAT, contract, criteria, PROJECTION, decision);

        long total = repository.count(query);
        List<DiscoveredProductDTO> products =
                total == 0 ? List.of() : assembler.assemble(repository.findPage(query), contract, PROJECTION);

        audit(contract, criteria, total, decision);
        return response(contract, criteria, products, total);
    }

    /**
     * The audit record the {@code audit_access} obligation asks for. It names the criteria the
     * caller searched by, never their values, so the log cannot become a copy of what was searched
     * for.
     */
    private void audit(
            ProductSearchContract contract,
            ProductSearchCriteria criteria,
            long total,
            Optional<PolicyDecision<ProductDiscoveryPolicyDecisionDetails>> decision) {
        if (!contract.obligations().contains(DiscoveryObligations.AUDIT_ACCESS)) {
            return;
        }
        log.info(
                "Product discover audit policy={} filtered={} sorted={} text={} page={} size={} results={}",
                ProductQueryPlanner.provenance(decision),
                criteria.filters().stream()
                        .map(filter -> filter.target().qualifiedName())
                        .toList(),
                criteria.sort(),
                criteria.hasText(),
                criteria.page(),
                criteria.size(),
                total);
    }

    private ProductDiscoveryResponseDTO response(
            ProductSearchContract contract,
            ProductSearchCriteria criteria,
            List<DiscoveredProductDTO> products,
            long total) {
        return ProductDiscoveryResponseDTO.builder()
                .products(products)
                .page(ProductDiscoveryResponseDTO.Page.of(criteria.page(), criteria.size(), products.size(), total))
                .policy(contract.isEnforced() ? policyBlock(contract) : null)
                .build();
    }

    /** What the caller was allowed, so a client can build its search form from it instead of guessing. */
    private static ProductDiscoveryResponseDTO.Policy policyBlock(ProductSearchContract contract) {
        return ProductDiscoveryResponseDTO.Policy.builder()
                .filterableFields(contract.filterableFields())
                .filterableAttributes(contract.filterableAttributes())
                .textSearchFields(contract.textSearchFields().stream()
                        .map(ProductField::apiName)
                        .toList())
                .maskedFields(contract.maskedFields())
                .maskedAttributes(contract.maskedAttributes())
                .maxPageSize(contract.maxPageSize())
                .obligations(contract.obligations())
                .build();
    }
}
