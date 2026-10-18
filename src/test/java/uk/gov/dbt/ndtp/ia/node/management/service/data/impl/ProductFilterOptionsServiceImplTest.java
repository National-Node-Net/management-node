/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.dbt.ndtp.ia.node.management.exception.AccessRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.InvalidSearchCriteriaException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductFilterOptionsResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductFilterOptionsResponseDTO.FilterOption;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductFilterOptionsResponseDTO.Value;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.ComparisonOperator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository.FacetCount;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.FacetQuery;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductFacetQueryBuilder;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductField;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductProjection;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductQueryPlanner;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchContract;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchCriteria;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchCriteriaFactory;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchQuery;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyProvenance;

/**
 * Covers the orchestration {@link ProductFilterOptionsServiceImpl} owns: running discover's front
 * half unchanged - the same contract, the same criteria validation and refusals, the same count -
 * and then assembling the panel from what {@link uk.gov.dbt.ndtp.ia.node.management.service.discovery.FilterOptionTargets}
 * and {@link uk.gov.dbt.ndtp.ia.node.management.service.discovery.FacetValues} decide.
 *
 * <p>Every collaborator - the planner, the criteria factory, the repository and the facet query
 * builder - is mocked, because what each of them decides is already proven in its own test; this
 * class proves only that this service calls them in the right order, with the right arguments, and
 * assembles their answers correctly. {@link ProductSearchContract} itself is built for real
 * ({@link ProductSearchContract#open()} or {@link ProductSearchContract#enforcing}), because the
 * filter panel's shape depends on what the contract actually permits.
 */
@ExtendWith(MockitoExtension.class)
class ProductFilterOptionsServiceImplTest {

    private static final ProductSearchQuery DUMMY_QUERY = new ProductSearchQuery("select", "count", Map.of());
    private static final FacetQuery DUMMY_FACET_QUERY = new FacetQuery("facet-sql", Map.of());

    @Mock
    private ProductQueryPlanner planner;

    @Mock
    private ProductSearchCriteriaFactory criteriaFactory;

    @Mock
    private ProductDiscoveryRepository repository;

    @Mock
    private ProductFacetQueryBuilder facetQueryBuilder;

    private ProductFilterOptionsServiceImpl service;

    @BeforeEach
    void createService() {
        service = new ProductFilterOptionsServiceImpl(planner, criteriaFactory, repository, facetQueryBuilder);
    }

    // ---------------------------------------------------------------------------------------
    // Building blocks
    // ---------------------------------------------------------------------------------------

    private static ProductSearchCriteria emptyCriteria() {
        return new ProductSearchCriteria(
                null, List.of(), List.of(ProductSearchCriteria.SortKey.byField(ProductField.NAME, false)), 0, 20);
    }

    private static ProductSearchCriteria criteriaWithFilters(FilterNode.Comparison... filters) {
        return new ProductSearchCriteria(
                null,
                List.of(filters),
                List.of(ProductSearchCriteria.SortKey.byField(ProductField.NAME, false)),
                0,
                20);
    }

    private static ProductDiscoveryPolicyDecisionDetails details(String json) {
        try {
            return new ObjectMapper().readValue(json, ProductDiscoveryPolicyDecisionDetails.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Test details are not valid JSON", e);
        }
    }

    private static PolicyDecision<ProductDiscoveryPolicyDecisionDetails> rawDecision(
            ProductDiscoveryPolicyDecisionDetails details) {
        return new PolicyDecision<>(
                true,
                List.of(),
                new PolicyProvenance("product.discover", "policies.product.discover/3.0.0", "exact"),
                details);
    }

    /** A contract that allows every field a caller could reasonably ask to filter the panel by. */
    private static ProductDiscoveryPolicyDecisionDetails permissiveDetails() {
        return details(
                """
                {"evaluation": "request",
                 "allowed_filtered_fields": ["name", "topic", "type", "source", "description",
                                             "organisation.key", "organisation.name"],
                 "allowed_filtered_attributes": ["identifiability", "quality_designation"],
                 "masked_filtered_fields": [],
                 "masked_filtered_attributes": [],
                 "visible_fields": ["name", "topic", "type", "source", "description",
                                    "organisation", "producer", "consumers", "subscribedBy", "policyAttributes"],
                 "text_search_fields": ["name", "description"],
                 "row_filter": {"type": "literal", "value": true},
                 "mask_sensitive_attributes": false,
                 "max_page_size": 50,
                 "obligations": []}""");
    }

    /** Stubs the front half every happy-path test shares: contract, sensitive names, criteria, query. */
    private void givenFrontHalf(ProductSearchContract contract, ProductSearchCriteria criteria) {
        when(planner.contractFor(any(), any())).thenReturn(contract);
        when(planner.sensitiveAttributeNames(contract)).thenReturn(Set.of());
        when(criteriaFactory.create(any(), eq(contract), any())).thenReturn(criteria);
        when(planner.compile(any(), eq(contract), eq(criteria), eq(ProductProjection.SUMMARY), any(), any()))
                .thenReturn(DUMMY_QUERY);
    }

    /** Stubs the one facet statement a test expects to run, with the given rows. */
    private void givenFacetCounts(ProductSearchContract contract, ProductSearchCriteria criteria, FacetCount... rows) {
        when(facetQueryBuilder.build(eq(contract), eq(criteria), any(), any())).thenReturn(DUMMY_FACET_QUERY);
        when(repository.findFacetCounts(DUMMY_FACET_QUERY)).thenReturn(List.of(rows));
    }

    private static FilterOption filterOption(ProductFilterOptionsResponseDTO response, String key) {
        return response.filters().stream()
                .filter(option -> option.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No filter option for key '" + key + "'"));
    }

    // ---------------------------------------------------------------------------------------
    // The front half is discover's, unchanged
    // ---------------------------------------------------------------------------------------

    @Test
    void returnsTotalFromTheSameCountDiscoverRuns() {
        ProductSearchContract contract = ProductSearchContract.open();
        ProductSearchCriteria criteria = emptyCriteria();
        givenFrontHalf(contract, criteria);
        when(repository.count(DUMMY_QUERY)).thenReturn(7L);
        when(repository.findFilterAttributeDefinitions()).thenReturn(List.of());
        givenFacetCounts(contract, criteria);

        ProductFilterOptionsResponseDTO response = service.filterOptions(null, null, Optional.empty());

        assertThat(response.totalElements()).isEqualTo(7L);
        verify(repository).count(DUMMY_QUERY);
    }

    /** Review Focus 1: a refusal from the criteria factory must stop the search before anything counts. */
    @Test
    void refusalsFromTheCriteriaFactoryPropagate_andNothingIsCounted() {
        ProductSearchContract contract = ProductSearchContract.open();
        when(planner.contractFor(any(), any())).thenReturn(contract);
        when(planner.sensitiveAttributeNames(contract)).thenReturn(Set.of());
        AccessRejectedException refusal = new AccessRejectedException(
                "Access denied by policy", List.of("filter.field_not_permitted:source"), "error-1");
        when(criteriaFactory.create(any(), eq(contract), any())).thenThrow(refusal);

        assertThatThrownBy(() -> service.filterOptions(null, null, Optional.empty()))
                .isSameAs(refusal);

        verifyNoInteractions(repository);
        verifyNoInteractions(facetQueryBuilder);
    }

    @Test
    void malformedCriteriaPropagateAs400() {
        ProductSearchContract contract = ProductSearchContract.open();
        when(planner.contractFor(any(), any())).thenReturn(contract);
        when(planner.sensitiveAttributeNames(contract)).thenReturn(Set.of());
        InvalidSearchCriteriaException malformed =
                new InvalidSearchCriteriaException("Operator 'lt' does not apply to the field 'name'");
        when(criteriaFactory.create(any(), eq(contract), any())).thenThrow(malformed);

        assertThatThrownBy(() -> service.filterOptions(null, null, Optional.empty()))
                .isSameAs(malformed);

        verify(repository, never()).count(any());
        verifyNoInteractions(facetQueryBuilder);
    }

    // ---------------------------------------------------------------------------------------
    // The policy block
    // ---------------------------------------------------------------------------------------

    /** Review Focus 3: policy switched off still consults the catalogue's sensitive names, as discover does. */
    @Test
    void openContract_hasNoPolicyBlock_andListsSensitiveAttributes() {
        ProductSearchContract contract = ProductSearchContract.open();
        ProductSearchCriteria criteria = emptyCriteria();
        Set<String> sensitiveNames = Set.of("tier");
        when(planner.contractFor(any(), any())).thenReturn(contract);
        when(planner.sensitiveAttributeNames(contract)).thenReturn(sensitiveNames);
        when(criteriaFactory.create(any(), eq(contract), eq(sensitiveNames))).thenReturn(criteria);
        when(planner.compile(any(), eq(contract), eq(criteria), eq(ProductProjection.SUMMARY), any(), any()))
                .thenReturn(DUMMY_QUERY);
        when(repository.count(DUMMY_QUERY)).thenReturn(0L);
        when(repository.findFilterAttributeDefinitions()).thenReturn(List.of());
        givenFacetCounts(contract, criteria);

        ProductFilterOptionsResponseDTO response = service.filterOptions(null, null, Optional.empty());

        assertThat(response.policy()).isNull();
        verify(planner).sensitiveAttributeNames(contract);
    }

    @Test
    void enforcedContract_carriesDiscoversPolicyBlock() {
        PolicyDecision<ProductDiscoveryPolicyDecisionDetails> decision = rawDecision(permissiveDetails());
        ProductSearchContract contract = ProductSearchContract.enforcing(decision);
        ProductSearchCriteria criteria = emptyCriteria();
        givenFrontHalf(contract, criteria);
        when(repository.count(DUMMY_QUERY)).thenReturn(0L);
        when(repository.findFilterAttributeDefinitions()).thenReturn(List.of());
        givenFacetCounts(contract, criteria);

        ProductFilterOptionsResponseDTO response = service.filterOptions(null, null, Optional.of(decision));

        assertThat(response.policy()).isEqualTo(ProductDiscoveryServiceImpl.policyBlock(contract));
    }

    // ---------------------------------------------------------------------------------------
    // Values: enumerated targets list them, free text does not
    // ---------------------------------------------------------------------------------------

    @Test
    void freeTextFieldsHaveNoValues_enumeratedOnesDo() {
        ProductSearchContract contract = ProductSearchContract.open();
        ProductSearchCriteria criteria = emptyCriteria();
        givenFrontHalf(contract, criteria);
        when(repository.count(DUMMY_QUERY)).thenReturn(0L);
        when(repository.findFilterAttributeDefinitions()).thenReturn(List.of());
        givenFacetCounts(
                contract,
                criteria,
                new FacetCount("field:format", "csv", 10),
                new FacetCount("field:format", "json", 5),
                new FacetCount("field:format", null, 2));

        ProductFilterOptionsResponseDTO response = service.filterOptions(null, null, Optional.empty());

        FilterOption name = filterOption(response, "field:name");
        assertThat(name.values()).isNull();
        assertThat(name.missingCount()).isNull();
        assertThat(name.truncated()).isNull();

        FilterOption format = filterOption(response, "field:format");
        assertThat(format.values()).containsExactly(new Value("csv", 10, false), new Value("json", 5, false));
        assertThat(format.missingCount()).isEqualTo(2L);
        assertThat(format.truncated()).isNull();
    }

    @Test
    void noValueListingTargets_runsNoFacetStatement() {
        ProductDiscoveryPolicyDecisionDetails details = details(
                """
                {"row_filter": {"type": "literal", "value": true},
                 "allowed_filtered_fields": ["name"],
                 "visible_fields": ["name"], "max_page_size": 20}""");
        PolicyDecision<ProductDiscoveryPolicyDecisionDetails> decision = rawDecision(details);
        ProductSearchContract contract = ProductSearchContract.enforcing(decision);
        ProductSearchCriteria criteria = emptyCriteria();
        givenFrontHalf(contract, criteria);
        when(repository.count(DUMMY_QUERY)).thenReturn(0L);
        when(repository.findFilterAttributeDefinitions()).thenReturn(List.of());

        ProductFilterOptionsResponseDTO response = service.filterOptions(null, null, Optional.of(decision));

        assertThat(response.filters()).extracting(FilterOption::key).containsExactly("field:name");
        verifyNoInteractions(facetQueryBuilder);
        verify(repository, never()).findFacetCounts(any());
    }

    // ---------------------------------------------------------------------------------------
    // Selected values
    // ---------------------------------------------------------------------------------------

    @Test
    void selectedReflectsEqInAnyOfFiltersOnly() {
        ProductSearchContract contract = ProductSearchContract.open();
        ProductSearchCriteria criteria = criteriaWithFilters(
                FilterNode.Comparison.ofField("format", ComparisonOperator.EQ, "csv"),
                FilterNode.Comparison.ofField("type", ComparisonOperator.NEQ, "topic"));
        givenFrontHalf(contract, criteria);
        when(repository.count(DUMMY_QUERY)).thenReturn(0L);
        when(repository.findFilterAttributeDefinitions()).thenReturn(List.of());
        givenFacetCounts(
                contract,
                criteria,
                new FacetCount("field:format", "csv", 10),
                new FacetCount("field:format", "json", 5),
                new FacetCount("field:type", "topic", 8),
                new FacetCount("field:type", "file", 3));

        ProductFilterOptionsResponseDTO response = service.filterOptions(null, null, Optional.empty());

        FilterOption format = filterOption(response, "field:format");
        assertThat(format.values()).containsExactly(new Value("csv", 10, true), new Value("json", 5, false));

        // NEQ never selects, even though the compared value ("topic") also names a facet value.
        FilterOption type = filterOption(response, "field:type");
        assertThat(type.values()).containsExactly(new Value("topic", 8, false), new Value("file", 3, false));
    }

    // ---------------------------------------------------------------------------------------
    // The request body
    // ---------------------------------------------------------------------------------------

    @Test
    void nullRequestBody_isTheEmptySearch() {
        ProductSearchContract contract = ProductSearchContract.open();
        ProductSearchCriteria criteria = emptyCriteria();
        givenFrontHalf(contract, criteria);
        when(repository.count(DUMMY_QUERY)).thenReturn(0L);
        when(repository.findFilterAttributeDefinitions()).thenReturn(List.of());
        givenFacetCounts(contract, criteria);

        service.filterOptions(null, null, Optional.empty());

        // The service does not substitute ProductDiscoveryRequestDTO.EMPTY itself; the criteria
        // factory already does that, exactly as it does for discover.
        verify(criteriaFactory).create(isNull(), eq(contract), any());
    }

    // ---------------------------------------------------------------------------------------
    // The caller's organisation reaches the facet query
    // ---------------------------------------------------------------------------------------

    @Test
    void targetsAreBuiltForTheCaller() {
        ProductSearchContract contract = ProductSearchContract.open();
        ProductSearchCriteria criteria = emptyCriteria();
        when(planner.contractFor(any(), any())).thenReturn(contract);
        when(planner.sensitiveAttributeNames(contract)).thenReturn(Set.of());
        when(criteriaFactory.create(any(), eq(contract), any())).thenReturn(criteria);
        when(planner.compile(any(), eq(contract), eq(criteria), eq(ProductProjection.SUMMARY), eq("ENV"), any()))
                .thenReturn(DUMMY_QUERY);
        when(repository.count(DUMMY_QUERY)).thenReturn(0L);
        when(repository.findFilterAttributeDefinitions()).thenReturn(List.of());
        when(facetQueryBuilder.build(eq(contract), eq(criteria), any(), eq("ENV")))
                .thenReturn(DUMMY_FACET_QUERY);
        when(repository.findFacetCounts(DUMMY_FACET_QUERY)).thenReturn(List.of());

        service.filterOptions(null, "ENV", Optional.empty());

        ArgumentCaptor<String> callerOrganisation = ArgumentCaptor.forClass(String.class);
        verify(facetQueryBuilder).build(eq(contract), eq(criteria), any(), callerOrganisation.capture());
        assertThat(callerOrganisation.getValue()).isEqualTo("ENV");
    }
}
