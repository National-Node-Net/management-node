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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import uk.gov.dbt.ndtp.ia.node.management.exception.AccessRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.InvalidSearchCriteriaException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductFilterOptionsResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductFilterOptionsResponseDTO.FilterOption;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductFilterOptionsResponseDTO.Value;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.ComparisonOperator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterTarget;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository.FacetCount;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository.FilterAttributeDefinition;
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

    private ListAppender<ILoggingEvent> appender;
    private Logger serviceLogger;

    @BeforeEach
    void createService() {
        service = new ProductFilterOptionsServiceImpl(planner, criteriaFactory, repository, facetQueryBuilder);

        serviceLogger = (Logger) LoggerFactory.getLogger(ProductFilterOptionsServiceImpl.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void releaseLog() {
        serviceLogger.detachAppender(appender);
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

    /**
     * A refusal from the compile step (a row filter or unmask condition the planner cannot
     * translate) must stop the search exactly as a criteria-factory refusal does: nothing after it
     * runs, however late in the sequence it happens to sit.
     */
    @Test
    void refusalFromCompile_preventsCountAndFilterAttributeDefinitionsAndFacetCounts() {
        ProductSearchContract contract = ProductSearchContract.open();
        ProductSearchCriteria criteria = emptyCriteria();
        when(planner.contractFor(any(), any())).thenReturn(contract);
        when(planner.sensitiveAttributeNames(contract)).thenReturn(Set.of());
        when(criteriaFactory.create(any(), eq(contract), any())).thenReturn(criteria);
        AccessRejectedException refusal = new AccessRejectedException(
                "Access denied by policy", List.of("policy.row_filter_uncompilable"), "error-2");
        when(planner.compile(any(), eq(contract), eq(criteria), eq(ProductProjection.SUMMARY), any(), any()))
                .thenThrow(refusal);

        assertThatThrownBy(() -> service.filterOptions(null, null, Optional.empty()))
                .isSameAs(refusal);

        verify(repository, never()).count(any());
        verify(repository, never()).findFilterAttributeDefinitions();
        verify(repository, never()).findFacetCounts(any());
        verifyNoInteractions(facetQueryBuilder);
    }

    /** The normal flow runs in exactly the order the brief specifies. */
    @Test
    void normalFlow_runsContractThenCriteriaThenCompileThenCount_inOrder() {
        ProductSearchContract contract = ProductSearchContract.open();
        ProductSearchCriteria criteria = emptyCriteria();
        givenFrontHalf(contract, criteria);
        when(repository.count(DUMMY_QUERY)).thenReturn(0L);
        when(repository.findFilterAttributeDefinitions()).thenReturn(List.of());
        givenFacetCounts(contract, criteria);

        service.filterOptions(null, null, Optional.empty());

        InOrder order = inOrder(planner, criteriaFactory, repository);
        order.verify(planner).contractFor(any(), any());
        order.verify(criteriaFactory).create(any(), eq(contract), any());
        order.verify(planner).compile(any(), eq(contract), eq(criteria), eq(ProductProjection.SUMMARY), any(), any());
        order.verify(repository).count(DUMMY_QUERY);
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

    /** Every operator the brief names as selecting, not only {@code EQ}. */
    @ParameterizedTest
    @EnumSource(
            value = ComparisonOperator.class,
            names = {"EQ", "IN", "ANY_OF"})
    void selectingOperators_markTheirValuesSelected(ComparisonOperator operator) {
        ProductSearchContract contract = ProductSearchContract.open();
        ProductSearchCriteria criteria = criteriaWithFilters(FilterNode.Comparison.ofField("format", operator, "csv"));
        givenFrontHalf(contract, criteria);
        when(repository.count(DUMMY_QUERY)).thenReturn(0L);
        when(repository.findFilterAttributeDefinitions()).thenReturn(List.of());
        givenFacetCounts(contract, criteria, new FacetCount("field:format", "csv", 10));

        ProductFilterOptionsResponseDTO response = service.filterOptions(null, null, Optional.empty());

        assertThat(filterOption(response, "field:format").values()).containsExactly(new Value("csv", 10, true));
    }

    /** The caller's filter value and the facet's own spelling are compared case-insensitively. */
    @Test
    void selectedMatchesCaseInsensitively() {
        ProductSearchContract contract = ProductSearchContract.open();
        ProductSearchCriteria criteria =
                criteriaWithFilters(FilterNode.Comparison.ofField("format", ComparisonOperator.EQ, "CSV"));
        givenFrontHalf(contract, criteria);
        when(repository.count(DUMMY_QUERY)).thenReturn(0L);
        when(repository.findFilterAttributeDefinitions()).thenReturn(List.of());
        givenFacetCounts(contract, criteria, new FacetCount("field:format", "csv", 10));

        ProductFilterOptionsResponseDTO response = service.filterOptions(null, null, Optional.empty());

        assertThat(filterOption(response, "field:format").values()).containsExactly(new Value("csv", 10, true));
    }

    /** {@code selected} works the same way for a policy attribute target as for a field. */
    @Test
    void selectedWorksForAnAttributeTarget() {
        ProductSearchContract contract = ProductSearchContract.open();
        FilterAttributeDefinition identifiability = new FilterAttributeDefinition(
                FilterScope.PRODUCT, "identifiability", "Identifiability", "STRING", false, false);
        ProductSearchCriteria criteria = criteriaWithFilters(
                FilterNode.Comparison.ofAttribute("identifiability", ComparisonOperator.EQ, "anonymised"));
        givenFrontHalf(contract, criteria);
        when(repository.count(DUMMY_QUERY)).thenReturn(0L);
        when(repository.findFilterAttributeDefinitions()).thenReturn(List.of(identifiability));
        givenFacetCounts(
                contract,
                criteria,
                new FacetCount("attribute:product.identifiability", "anonymised", 7),
                new FacetCount("attribute:product.identifiability", "non_personal", 3));

        ProductFilterOptionsResponseDTO response = service.filterOptions(null, null, Optional.empty());

        FilterOption identifiabilityOption = filterOption(response, "attribute:product.identifiability");
        assertThat(identifiabilityOption.values())
                .containsExactly(new Value("anonymised", 7, true), new Value("non_personal", 3, false));
    }

    /** The boolean {@code isOwnProduct} target selects exactly as a text one does. */
    @Test
    void selectedWorksForTheBooleanIsOwnProductTarget() {
        ProductSearchContract contract = ProductSearchContract.open();
        ProductSearchCriteria criteria =
                criteriaWithFilters(FilterNode.Comparison.ofField("isOwnProduct", ComparisonOperator.EQ, true));
        givenFrontHalf(contract, criteria);
        when(repository.count(DUMMY_QUERY)).thenReturn(0L);
        when(repository.findFilterAttributeDefinitions()).thenReturn(List.of());
        givenFacetCounts(
                contract,
                criteria,
                new FacetCount("field:isOwnProduct", "true", 6),
                new FacetCount("field:isOwnProduct", "false", 9));

        ProductFilterOptionsResponseDTO response = service.filterOptions(null, "ENV", Optional.empty());

        FilterOption isOwnProduct = filterOption(response, "field:isOwnProduct");
        assertThat(isOwnProduct.values())
                .containsExactly(new Value(Boolean.TRUE, 6, true), new Value(Boolean.FALSE, 9, false));
    }

    /**
     * The regression this task was raised for: an {@code organisation.key} filter must select its
     * own value in the panel, which it can only do once {@code FilterOptionTargets} builds the same
     * {@link FilterTarget} the criteria side parses for it.
     */
    @Test
    void organisationKeyEqFilter_marksItsValueSelected() {
        ProductSearchContract contract = ProductSearchContract.open();
        ProductSearchCriteria criteria = criteriaWithFilters(new FilterNode.Comparison(
                FilterTarget.parse(null, true, "organisation.key"), ComparisonOperator.EQ, List.of("ENV")));
        givenFrontHalf(contract, criteria);
        when(repository.count(DUMMY_QUERY)).thenReturn(0L);
        when(repository.findFilterAttributeDefinitions()).thenReturn(List.of());
        givenFacetCounts(
                contract,
                criteria,
                new FacetCount("field:organisation.key", "ENV", 4),
                new FacetCount("field:organisation.key", "HEG", 2));

        ProductFilterOptionsResponseDTO response = service.filterOptions(null, null, Optional.empty());

        FilterOption organisationKey = filterOption(response, "field:organisation.key");
        assertThat(organisationKey.values()).containsExactly(new Value("ENV", 4, true), new Value("HEG", 2, false));
    }

    // ---------------------------------------------------------------------------------------
    // The request body
    // ---------------------------------------------------------------------------------------

    @Test
    void nullRequestBody_isTheEmptySearch() {
        ProductSearchContract contract = ProductSearchContract.open();
        ProductSearchCriteria criteria = emptyCriteria();
        givenFrontHalf(contract, criteria);
        when(repository.count(DUMMY_QUERY)).thenReturn(5L);
        when(repository.findFilterAttributeDefinitions()).thenReturn(List.of());
        givenFacetCounts(contract, criteria);

        ProductFilterOptionsResponseDTO response = service.filterOptions(null, null, Optional.empty());

        // The service does not substitute ProductDiscoveryRequestDTO.EMPTY itself; the criteria
        // factory already does that, exactly as it does for discover.
        verify(criteriaFactory).create(isNull(), eq(contract), any());
        assertThat(response.totalElements()).isEqualTo(5L);
        assertThat(response.filters()).isNotEmpty();
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

        verify(facetQueryBuilder).build(eq(contract), eq(criteria), any(), eq("ENV"));
    }

    // ---------------------------------------------------------------------------------------
    // The audit line
    // ---------------------------------------------------------------------------------------

    @Test
    void noAuditObligation_logsNothing() {
        ProductSearchContract contract = ProductSearchContract.open();
        ProductSearchCriteria criteria = emptyCriteria();
        givenFrontHalf(contract, criteria);
        when(repository.count(DUMMY_QUERY)).thenReturn(0L);
        when(repository.findFilterAttributeDefinitions()).thenReturn(List.of());
        givenFacetCounts(contract, criteria);

        service.filterOptions(null, null, Optional.empty());

        assertThat(appender.list).isEmpty();
    }

    @Test
    void auditObligation_logsFilterNamesNeverValues() {
        ProductDiscoveryPolicyDecisionDetails details = details(
                """
                {"row_filter": {"type": "literal", "value": true},
                 "allowed_filtered_fields": ["format"],
                 "visible_fields": ["name"], "max_page_size": 20,
                 "obligations": ["audit_access"]}""");
        PolicyDecision<ProductDiscoveryPolicyDecisionDetails> decision = rawDecision(details);
        ProductSearchContract contract = ProductSearchContract.enforcing(decision);
        ProductSearchCriteria criteria =
                criteriaWithFilters(FilterNode.Comparison.ofField("format", ComparisonOperator.EQ, "top-secret-value"));
        givenFrontHalf(contract, criteria);
        when(repository.count(DUMMY_QUERY)).thenReturn(3L);
        when(repository.findFilterAttributeDefinitions()).thenReturn(List.of());
        givenFacetCounts(contract, criteria, new FacetCount("field:format", "top-secret-value", 3));

        service.filterOptions(null, null, Optional.of(decision));

        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message)
                .startsWith("Product discover filter audit")
                .contains("filtered=[format]")
                .contains("filters=[field:format]")
                .contains("results=3")
                .doesNotContain("top-secret-value");
    }

    // ---------------------------------------------------------------------------------------
    // The sensitive set is discover's own
    // ---------------------------------------------------------------------------------------

    /**
     * The sensitive set the panel filters by is discover's own, read once from the planner and
     * used for both the criteria and the offered targets - never derived from the definitions'
     * own flags, which are scope-limited and de-duplicated per name.
     */
    @Test
    void sensitiveSetGivenToTheTargets_isExactlyThePlanners() {
        ProductSearchContract contract = ProductSearchContract.enforcing(
                rawDecision(
                        details(
                                """
                {"allowed_filtered_fields": ["name"],
                 "allowed_filtered_attributes": ["identifiability", "quality_designation"],
                 "row_filter": {"type": "literal", "value": true},
                 "mask_sensitive_attributes": true,
                 "obligations": []}""")));
        ProductSearchCriteria criteria = emptyCriteria();
        Set<String> plannersSensitiveNames = Set.of("identifiability");
        // Flagged the other way round from the planner's set, so only the planner's can explain
        // which one survives.
        FilterAttributeDefinition identifiability = new FilterAttributeDefinition(
                FilterScope.PRODUCT, "identifiability", "Identifiability", "STRING", false, false);
        FilterAttributeDefinition qualityDesignation = new FilterAttributeDefinition(
                FilterScope.PRODUCT, "quality_designation", "Quality designation", "STRING", false, true);
        when(planner.contractFor(any(), any())).thenReturn(contract);
        when(planner.sensitiveAttributeNames(contract)).thenReturn(plannersSensitiveNames);
        when(criteriaFactory.create(any(), eq(contract), eq(plannersSensitiveNames)))
                .thenReturn(criteria);
        when(planner.compile(any(), eq(contract), eq(criteria), eq(ProductProjection.SUMMARY), any(), any()))
                .thenReturn(DUMMY_QUERY);
        when(repository.count(DUMMY_QUERY)).thenReturn(0L);
        when(repository.findFilterAttributeDefinitions()).thenReturn(List.of(identifiability, qualityDesignation));
        givenFacetCounts(contract, criteria);

        ProductFilterOptionsResponseDTO response = service.filterOptions(null, "ENV", Optional.empty());

        assertThat(response.filters())
                .extracting(FilterOption::key)
                .contains("attribute:product.quality_designation")
                .doesNotContain("attribute:product.identifiability");
        verify(planner, times(1)).sensitiveAttributeNames(contract);
    }

    // ---------------------------------------------------------------------------------------
    // An unknown organisation is no organisation
    // ---------------------------------------------------------------------------------------

    /** {@code UNKNOWN_ORG} is no organisation: nothing downstream sees it, and no own-product filter. */
    @Test
    void unknownOrganisation_isTreatedAsAbsent() {
        ProductSearchContract contract = ProductSearchContract.open();
        ProductSearchCriteria criteria = emptyCriteria();
        givenFrontHalf(contract, criteria);
        when(repository.count(DUMMY_QUERY)).thenReturn(0L);
        when(repository.findFilterAttributeDefinitions()).thenReturn(List.of());
        givenFacetCounts(contract, criteria);

        ProductFilterOptionsResponseDTO response = service.filterOptions(null, "UNKNOWN_ORG", Optional.empty());

        verify(planner).compile(any(), eq(contract), eq(criteria), eq(ProductProjection.SUMMARY), isNull(), any());
        verify(facetQueryBuilder).build(eq(contract), eq(criteria), any(), isNull());
        assertThat(response.filters()).extracting(FilterOption::key).doesNotContain("field:isOwnProduct");
    }
}
