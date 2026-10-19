/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.model.UnknownIdentifiers;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductFilterOptionsResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductFilterOptionsResponseDTO.FilterOption;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductFilterOptionsResponseDTO.Target;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductFilterOptionsResponseDTO.TextSearch;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.ComparisonOperator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterTarget;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository.FacetCount;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository.FilterAttributeDefinition;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductFilterOptionsService;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.DiscoveryObligations;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.FacetValues;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.FilterOptionTarget;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.FilterOptionTargets;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductFacetQueryBuilder;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductField;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductProjection;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductQueryPlanner;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchContract;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchCriteria;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchCriteriaFactory;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchQuery;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;

/**
 * Builds the filter panel by running discover's front half unchanged and adding one thing it does
 * not: the distinct values each permitted filter holds.
 *
 * <ol>
 *   <li><b>plan</b> - the same contract discovery would get ({@link ProductQueryPlanner});
 *   <li><b>criteria</b> - the same validation and refusals discovery applies
 *       ({@link ProductSearchCriteriaFactory});
 *   <li><b>count</b> - the same total discovery's query would return, under the same row filter
 *       ({@link ProductDiscoveryRepository#count});
 *   <li><b>offer</b> - which filters the contract permits ({@link FilterOptionTargets});
 *   <li><b>facet</b> - at most one statement counting every permitted, value-listing filter at once
 *       ({@link ProductFacetQueryBuilder}), summarised per target ({@link FacetValues}).
 * </ol>
 *
 * <p>Nothing here may widen what discovery would show: the contract, the criteria and the row
 * filter are exactly discovery's, so a caller can never be offered a filter value they could not
 * also reach by searching for it.
 */
@Service
@Slf4j
public class ProductFilterOptionsServiceImpl implements ProductFilterOptionsService {

    /** Names this endpoint in the planner's refusal and audit lines. */
    private static final String WHAT = "Product discover filter";

    /** The operators {@code selected} is read from; every other operator never selects a value. */
    private static final Set<ComparisonOperator> SELECTING_OPERATORS =
            Set.of(ComparisonOperator.EQ, ComparisonOperator.IN, ComparisonOperator.ANY_OF);

    private final ProductQueryPlanner planner;
    private final ProductSearchCriteriaFactory criteriaFactory;
    private final ProductDiscoveryRepository repository;
    private final ProductFacetQueryBuilder facetQueryBuilder;

    public ProductFilterOptionsServiceImpl(
            ProductQueryPlanner planner,
            ProductSearchCriteriaFactory criteriaFactory,
            ProductDiscoveryRepository repository,
            ProductFacetQueryBuilder facetQueryBuilder) {
        this.planner = planner;
        this.criteriaFactory = criteriaFactory;
        this.repository = repository;
        this.facetQueryBuilder = facetQueryBuilder;
    }

    @Override
    public ProductFilterOptionsResponseDTO filterOptions(
            ProductDiscoveryRequestDTO request,
            String callerOrganisationKey,
            Optional<PolicyDecision<ProductDiscoveryPolicyDecisionDetails>> decision) {
        String callerOrganisation = UnknownIdentifiers.knownOrganisationOrNull(callerOrganisationKey);

        ProductSearchContract contract = planner.contractFor(WHAT, decision);
        // Discover's own sensitive set, read once and used for both the criteria and the panel, so
        // the panel can never offer an attribute discover would refuse, nor withhold one it accepts.
        Set<String> sensitiveAttributeNames = planner.sensitiveAttributeNames(contract);
        ProductSearchCriteria criteria = criteriaFactory.create(request, contract, sensitiveAttributeNames);
        ProductSearchQuery query =
                planner.compile(WHAT, contract, criteria, ProductProjection.SUMMARY, callerOrganisation, decision);

        long total = repository.count(query);

        List<FilterAttributeDefinition> definitions = repository.findFilterAttributeDefinitions();
        List<FilterOptionTarget> targets =
                FilterOptionTargets.permitted(contract, definitions, sensitiveAttributeNames, callerOrganisation);
        Map<String, List<FacetCount>> rowsByFacet = facetCounts(contract, criteria, targets, callerOrganisation);

        List<FilterOption> filters = targets.stream()
                .map(target -> filterOption(target, rowsByFacet, criteria))
                .toList();

        audit(contract, criteria, targets, total, decision);

        return ProductFilterOptionsResponseDTO.builder()
                .totalElements(total)
                .textSearch(textSearch(contract))
                .filters(filters)
                .policy(contract.isEnforced() ? ProductDiscoveryServiceImpl.policyBlock(contract) : null)
                .build();
    }

    /**
     * At most one facet statement, run only when some permitted target is worth listing values
     * for. Its rows are grouped by {@code facet}, which {@link ProductFacetQueryBuilder} binds as
     * each target's own {@link FilterOptionTarget#key()}.
     */
    private Map<String, List<FacetCount>> facetCounts(
            ProductSearchContract contract,
            ProductSearchCriteria criteria,
            List<FilterOptionTarget> targets,
            String callerOrganisation) {
        List<FilterOptionTarget> listing =
                targets.stream().filter(FilterOptionTarget::listsValues).toList();
        if (listing.isEmpty()) {
            return Map.of();
        }
        List<FacetCount> rows =
                repository.findFacetCounts(facetQueryBuilder.build(contract, criteria, listing, callerOrganisation));
        return rows.stream().collect(Collectors.groupingBy(FacetCount::facet));
    }

    /**
     * One filter option. A target that does not list values carries no {@code values}, {@code
     * missingCount} or {@code truncated} - not even zero or false - because those three describe
     * values nobody decided were worth showing.
     */
    private static FilterOption filterOption(
            FilterOptionTarget target, Map<String, List<FacetCount>> rowsByFacet, ProductSearchCriteria criteria) {
        FilterOption.FilterOptionBuilder option = FilterOption.builder()
                .key(target.key())
                .target(targetOf(target))
                .label(target.label())
                .dataType(target.dataType())
                .multiValued(target.multiValued())
                .operators(target.operators().stream()
                        .map(ComparisonOperator::wireName)
                        .toList())
                .defaultOperator(target.defaultOperator().wireName())
                .sortable(target.sortable());
        if (!target.listsValues()) {
            return option.build();
        }
        FacetValues.Summary summary = FacetValues.summarise(
                target, rowsByFacet.getOrDefault(target.key(), List.of()), selectedValues(target, criteria));
        return option.values(summary.values())
                .missingCount(summary.missingCount() == 0 ? null : summary.missingCount())
                .truncated(summary.truncated() ? Boolean.TRUE : null)
                .build();
    }

    /** What a filter option targets, in the shape a discover criterion names it. */
    private static Target targetOf(FilterOptionTarget option) {
        FilterTarget target = option.target();
        return target.field()
                ? new Target(target.qualifiedName(), null, null)
                : new Target(null, target.scope(), target.name());
    }

    /**
     * The lower-cased values of the caller's own criteria filters on this target whose operator is
     * {@code EQ}, {@code IN} or {@code ANY_OF}. Every other operator - a {@code NEQ} naming the same
     * value included - never marks a value selected.
     */
    private static Set<String> selectedValues(FilterOptionTarget target, ProductSearchCriteria criteria) {
        Set<String> selected = new HashSet<>();
        for (FilterNode.Comparison filter : criteria.filters()) {
            if (!filter.target().equals(target.target()) || !SELECTING_OPERATORS.contains(filter.operator())) {
                continue;
            }
            for (Object value : filter.values()) {
                selected.add(String.valueOf(value).toLowerCase(Locale.ROOT));
            }
        }
        return selected;
    }

    private static TextSearch textSearch(ProductSearchContract contract) {
        List<String> fields =
                contract.textSearchFields().stream().map(ProductField::apiName).toList();
        return new TextSearch(!fields.isEmpty(), fields);
    }

    /**
     * The audit record the {@code audit_access} obligation asks for. It names the criteria the
     * caller filtered by and the filters the panel offered, never any value, so the log cannot
     * become a copy of what was searched for.
     */
    private void audit(
            ProductSearchContract contract,
            ProductSearchCriteria criteria,
            List<FilterOptionTarget> targets,
            long total,
            Optional<PolicyDecision<ProductDiscoveryPolicyDecisionDetails>> decision) {
        if (!contract.obligations().contains(DiscoveryObligations.AUDIT_ACCESS)) {
            return;
        }
        log.info(
                "Product discover filter audit policy={} filtered={} text={} filters={} results={}",
                ProductQueryPlanner.provenance(decision),
                criteria.filters().stream()
                        .map(filter -> filter.target().qualifiedName())
                        .toList(),
                criteria.hasText(),
                targets.stream().map(FilterOptionTarget::key).toList(),
                total);
    }
}
