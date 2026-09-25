/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;
import uk.gov.dbt.ndtp.ia.node.management.exception.AccessRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.DiscoveredProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryFilterDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoverySortDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.DiscoveredProductAssembler;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.DiscoverySchema;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductProjection;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductQueryPlanner;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchContract;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchCriteria;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchCriteriaFactory;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchQuery;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchQueryBuilder;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionClient;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyProvenance;

/**
 * Covers how a search is orchestrated: the contract a decision (or its absence) yields, the query
 * that follows from it, and what the response says.
 *
 * <p>What a decision <em>means</em> - and what the absence of one means - is
 * {@link ProductQueryPlanner}'s, shared with the view endpoint and tested in
 * {@code ProductQueryPlannerTest}. This class exercises the search it plans, plus one end-to-end
 * refusal proving the two are wired together.
 *
 * <p>Two of these tests exist for reasons larger than the code they cover. One is that no
 * {@link PolicyDecisionClient} is a collaborator at all - the decision handed in is the only one
 * taken, so there can be no per-product call that answers differently from the query. The other is
 * that with policy enforcement switched off the search still runs, unrestricted and with no policy
 * block, rather than failing or quietly returning nothing.
 *
 * <p>The planner, the query builder and its compiler are real rather than mocked: the SQL is what
 * the contract means, so a test that stubbed it away would prove nothing about the contract being
 * applied. Only the database and the assembly of rows are stubbed.
 *
 * <p>A search returns a {@link ProductProjection#SUMMARY} of each product, and that too is a claim
 * about the SQL: the columns a search does not return are not in its {@code SELECT} list, so they
 * are never read. The projection narrows the <em>response</em> only - what may be filtered, sorted
 * and text-searched on is the contract's business and is unchanged by it.
 */
@ExtendWith(MockitoExtension.class)
class ProductDiscoveryServiceImplTest {

    private static final int DEFAULT_MAX_PAGE_SIZE = ProductSearchContract.DEFAULT_MAX_PAGE_SIZE;

    @Mock
    private ProductDiscoveryRepository repository;

    @Mock
    private DiscoveredProductAssembler assembler;

    private final ProductSearchQueryBuilder queryBuilder = new ProductSearchQueryBuilder(new DiscoverySchema("mn"));

    /** The service as configured with policy enforcement on - the normal case. */
    private ProductDiscoveryServiceImpl service() {
        return service(true);
    }

    /**
     * @param policyEnforcementEnabled the {@code application.opa.enabled} master switch, which is
     *     what tells "no decision because policy is off" from "no decision although it is on"
     */
    private ProductDiscoveryServiceImpl service(boolean policyEnforcementEnabled) {
        ProductQueryPlanner planner =
                new ProductQueryPlanner(queryBuilder, repository, opaProperties(policyEnforcementEnabled));
        return new ProductDiscoveryServiceImpl(planner, new ProductSearchCriteriaFactory(), repository, assembler);
    }

    private static OpaProperties opaProperties(boolean enabled) {
        return new OpaProperties(
                enabled,
                "http://localhost:8181",
                "/v1/data/dispatch/decision",
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                List.of("content-type"),
                false,
                false);
    }

    // ---------------------------------------------------------------------------------------
    // Decisions and contracts
    // ---------------------------------------------------------------------------------------

    /** A decision carrying the given rule details, as the PEP would hand it to the handler. */
    private static Optional<PolicyDecision<ProductDiscoveryPolicyDecisionDetails>> decision(
            boolean allow, ProductDiscoveryPolicyDecisionDetails details) {
        return Optional.of(new PolicyDecision<>(
                allow,
                List.of(),
                new PolicyProvenance("product.discover", "policies.product.discover/3.0.0", "exact"),
                details));
    }

    /**
     * Details in the shape the rule returns them, read through Jackson so the test states the
     * contract as a policy author would write it rather than as Java holds it.
     */
    private static ProductDiscoveryPolicyDecisionDetails details(String json) {
        try {
            return new ObjectMapper().readValue(json, ProductDiscoveryPolicyDecisionDetails.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Test details are not valid JSON", e);
        }
    }

    /** A contract that allows everything a caller could reasonably ask for. */
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

    private void databaseReturns(long total, List<DiscoveredProductDTO> products) {
        when(repository.count(any())).thenReturn(total);
        if (total > 0) {
            when(repository.findPage(any())).thenReturn(List.of(Map.of("id", 1L)));
            when(assembler.assemble(any(), any(), any())).thenReturn(products);
        }
    }

    private static DiscoveredProductDTO product(long id, String name) {
        return DiscoveredProductDTO.builder().id(id).name(name).build();
    }

    private static ProductSearchQuery capturedQuery(ProductDiscoveryRepository repository) {
        ArgumentCaptor<ProductSearchQuery> query = ArgumentCaptor.forClass(ProductSearchQuery.class);
        verify(repository).count(query.capture());
        return query.getValue();
    }

    /**
     * Just the {@code SELECT} list of the page statement - what the search reads. Asserted apart
     * from the rest of the statement because a column may legitimately appear in the {@code WHERE}
     * or {@code ORDER BY} of a search that does not return it.
     */
    private static String selectList(ProductSearchQuery query) {
        String sql = query.pageSql();
        return sql.substring("SELECT ".length(), sql.indexOf(" FROM "));
    }

    /** The projection the assembler was asked to build the response to. */
    private ProductProjection capturedProjection() {
        ArgumentCaptor<ProductProjection> projection = ArgumentCaptor.forClass(ProductProjection.class);
        verify(assembler).assemble(any(), any(), projection.capture());
        return projection.getValue();
    }

    // ---------------------------------------------------------------------------------------
    // Policy switched off
    // ---------------------------------------------------------------------------------------

    @Test
    void discover_policySwitchedOff_searchesUnrestrictedAndReturnsProducts() {
        databaseReturns(1, List.of(product(3, "FloodRiskMapZones")));

        ProductDiscoveryResponseDTO response = service(false).discover(null, Optional.empty());

        assertThat(response.products()).extracting(DiscoveredProductDTO::name).containsExactly("FloodRiskMapZones");
        assertThat(response.page().totalElements()).isEqualTo(1);
        // The row filter is the open contract's: every product qualifies.
        assertThat(capturedQuery(repository).pageSql()).contains("WHERE (1 = 1)");
    }

    @Test
    void discover_policySwitchedOff_omitsThePolicyBlock() {
        databaseReturns(0, List.of());

        assertThat(service(false).discover(null, Optional.empty()).policy()).isNull();
    }

    @Test
    void discover_policySwitchedOff_appliesTheCallersCriteriaButRestrictsNothing() {
        databaseReturns(0, List.of());

        // A filter on an attribute no contract listed: with policy off, nothing is refused.
        service(false)
                .discover(
                        ProductDiscoveryRequestDTO.builder()
                                .text("flood")
                                .filters(List.of(ProductDiscoveryFilterDTO.builder()
                                        .attribute("identifiability")
                                        .values(List.of("non_personal"))
                                        .build()))
                                .build(),
                        Optional.empty());

        ProductSearchQuery query = capturedQuery(repository);
        assertThat(query.pageSql()).contains("EXISTS").contains("LIKE");
        assertThat(query.parameters()).containsValue("identifiability").containsValue("%flood%");
    }

    @Test
    void discover_policySwitchedOff_usesTheConfiguredDefaultPageSizeAsTheLimit() {
        databaseReturns(0, List.of());

        ProductDiscoveryResponseDTO response = service(false)
                .discover(ProductDiscoveryRequestDTO.builder().size(500).build(), Optional.empty());

        assertThat(response.page().size()).isEqualTo(DEFAULT_MAX_PAGE_SIZE);
    }

    // ---------------------------------------------------------------------------------------
    // Enforced searches
    // ---------------------------------------------------------------------------------------

    @Test
    void discover_rowFilter_becomesTheQuerysWhereClause() {
        databaseReturns(0, List.of());
        ProductDiscoveryPolicyDecisionDetails details = details(
                """
                {"row_filter": {"type": "comparison", "attribute": "identifiability",
                                "operator": "in", "values": ["non_personal", "anonymised"]},
                 "visible_fields": ["name"], "max_page_size": 20}""");

        service().discover(null, decision(true, details));

        ProductSearchQuery query = capturedQuery(repository);
        assertThat(query.pageSql()).contains("EXISTS (SELECT 1 FROM \"mn\".policy_attribute_live_value a");
        assertThat(query.parameters()).containsValue("identifiability");
        assertThat(query.parameters().values()).anySatisfy(value -> assertThat(value)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.collection(String.class))
                .containsExactly("non_personal", "anonymised"));
    }

    @Test
    void discover_maskedField_isNeverSelected() {
        databaseReturns(0, List.of());
        ProductDiscoveryPolicyDecisionDetails details = details(
                """
                {"row_filter": {"type": "literal", "value": true},
                 "visible_fields": ["name", "source"],
                 "masked_filtered_fields": ["source"],
                 "max_page_size": 20}""");

        service().discover(null, decision(true, details));

        // The guarantee is not "stripped afterwards" but "never read".
        assertThat(capturedQuery(repository).pageSql()).contains("p.name").doesNotContain("p.source");
    }

    @Test
    void discover_deniedDecision_findsNothing() {
        databaseReturns(0, List.of());

        ProductDiscoveryResponseDTO response = service().discover(null, decision(false, permissiveDetails()));

        // A denied request never reaches the search; should one, its row filter matches no product.
        assertThat(capturedQuery(repository).pageSql()).contains("WHERE (1 = 0)");
        assertThat(response.products()).isEmpty();
    }

    @Test
    void discover_sizeAbovePolicysMaximum_isClampedNotRefused() {
        databaseReturns(0, List.of());

        ProductDiscoveryResponseDTO response = service()
                .discover(ProductDiscoveryRequestDTO.builder().size(500).build(), decision(true, permissiveDetails()));

        assertThat(response.page().size()).isEqualTo(50);
        assertThat(response.policy().maxPageSize()).isEqualTo(50);
    }

    @Test
    void discover_policyBlock_reportsWhatTheCallerWasAllowed() {
        databaseReturns(0, List.of());

        ProductDiscoveryResponseDTO.Policy policy =
                service().discover(null, decision(true, permissiveDetails())).policy();

        assertThat(policy.filterableFields()).contains("name", "organisation.key");
        assertThat(policy.filterableAttributes()).containsExactly("identifiability", "quality_designation");
        assertThat(policy.textSearchFields()).containsExactly("name", "description");
        assertThat(policy.maxPageSize()).isEqualTo(50);
    }

    @Test
    void discover_obligationsTheServiceKnows_areFulfilledAndReported() {
        databaseReturns(0, List.of());
        ProductDiscoveryPolicyDecisionDetails details = details(
                """
                {"row_filter": {"type": "literal", "value": true},
                 "visible_fields": ["name"], "max_page_size": 20,
                 "obligations": ["audit_access", "mask_response", "aggregate_before_release"]}""");

        ProductDiscoveryResponseDTO response = service().discover(null, decision(true, details));

        assertThat(response.policy().obligations())
                .containsExactly("audit_access", "mask_response", "aggregate_before_release");
    }

    @Test
    void discover_emptyResult_doesNotLoadAPage() {
        databaseReturns(0, List.of());

        ProductDiscoveryResponseDTO response = service().discover(null, decision(true, permissiveDetails()));

        assertThat(response.products()).isEmpty();
        assertThat(response.page().totalPages()).isZero();
        verify(repository, never()).findPage(any());
        verifyNoInteractions(assembler);
    }

    @Test
    void discover_paging_isReportedFromTheTotalTheDatabaseCounted() {
        databaseReturns(42, List.of(product(1, "A")));

        ProductDiscoveryResponseDTO response = service()
                .discover(
                        ProductDiscoveryRequestDTO.builder().page(1).size(20).build(),
                        decision(true, permissiveDetails()));

        assertThat(response.page().number()).isEqualTo(1);
        assertThat(response.page().size()).isEqualTo(20);
        assertThat(response.page().totalElements()).isEqualTo(42);
        assertThat(response.page().totalPages()).isEqualTo(3);
        assertThat(capturedQuery(repository).parameters()).containsValue(20L);
        // The page size asked for is 20, but this page carries one product: the count is taken from
        // the products themselves, so a caller never has to infer it.
        assertThat(response.page().numberOfElements()).isEqualTo(1);
        assertThat(response.products()).hasSize(response.page().numberOfElements());
    }

    @Test
    void discover_emptyResult_reportsNoElementsOnThePage() {
        databaseReturns(0, List.of());

        ProductDiscoveryResponseDTO response = service().discover(null, decision(true, permissiveDetails()));

        assertThat(response.page().numberOfElements()).isZero();
        assertThat(response.page().totalElements()).isZero();
    }

    @Test
    void discover_criteriaPolicyDoesNotPermit_areRefusedNamingWhatWasRefused() {
        ProductDiscoveryPolicyDecisionDetails details = details(
                """
                {"row_filter": {"type": "literal", "value": true},
                 "allowed_filtered_fields": ["name"],
                 "visible_fields": ["name"], "max_page_size": 20}""");

        var service = service();
        var request = ProductDiscoveryRequestDTO.builder()
                .filters(List.of(ProductDiscoveryFilterDTO.builder()
                        .field("source")
                        .values(List.of("x"))
                        .build()))
                .build();
        var taken = decision(true, details);
        assertThatThrownBy(() -> service.discover(request, taken))
                .isInstanceOf(AccessRejectedException.class)
                .extracting(e -> ((AccessRejectedException) e).getReasons())
                .isEqualTo(List.of("filter.field_not_permitted:source"));
        verify(repository, never()).count(any());
    }

    // ---------------------------------------------------------------------------------------
    // Fail closed: the planner refuses, and the refusal reaches the caller
    // ---------------------------------------------------------------------------------------

    /**
     * Every planner refusal is tested in {@code ProductQueryPlannerTest}; this one is here to prove
     * the service is actually wired to the planner rather than deciding for itself. The failure it
     * guards against is the quiet one: policy is switched on, the enforcement point does not run,
     * and discovery treats the missing decision as "policy is off" - returning every product to a
     * caller nobody authorised, in a response that looks entirely normal.
     */
    @Test
    void discover_policyOnButNoDecisionReachedTheHandler_refusesRatherThanSearchingUnrestricted() {
        var service = service(true);
        assertThatThrownBy(() -> service.discover(null, Optional.empty()))
                .isInstanceOf(AccessRejectedException.class)
                .hasMessage("Access denied by policy")
                .extracting(e -> ((AccessRejectedException) e).getReasons())
                .isEqualTo(List.of(ProductQueryPlanner.REASON_ENFORCEMENT_MISSING));
        verifyNoInteractions(repository);
        verifyNoInteractions(assembler);
    }

    // ---------------------------------------------------------------------------------------
    // One decision per search
    // ---------------------------------------------------------------------------------------

    /**
     * The service has no policy client to call. Discovery asks the PDP once - in the enforcement
     * point, before the handler - and everything that varies per product is a condition the
     * database evaluates, so no product can be judged by an answer the query did not use.
     */
    @Test
    void discoveryService_hasNoPolicyClientToCall() {
        assertThat(ProductDiscoveryServiceImpl.class.getDeclaredFields())
                .noneMatch(field -> PolicyDecisionClient.class.isAssignableFrom(field.getType()));
    }

    @Test
    void discover_readsTheDatabaseOnceForTheTotalAndOnceForThePage() {
        databaseReturns(3, List.of(product(1, "A")));

        service().discover(null, decision(true, permissiveDetails()));

        verify(repository).count(any());
        verify(repository).findPage(any());
        // The blocks are the assembler's business, and it batches them; nothing here is per product.
        verify(repository, never()).findAttributes(any(), anyCollection(), anySet(), anyBoolean());
    }

    @Test
    void discover_page_isAssembledUnderTheSameContractTheQueryWasBuiltFrom() {
        databaseReturns(1, List.of(product(3, "FloodRiskMapZones")));

        service().discover(null, decision(true, permissiveDetails()));

        ArgumentCaptor<ProductSearchContract> contract = ArgumentCaptor.forClass(ProductSearchContract.class);
        verify(assembler).assemble(any(), contract.capture(), any());
        assertThat(contract.getValue().isEnforced()).isTrue();
        assertThat(contract.getValue().rowFilter()).isInstanceOf(FilterNode.Literal.class);
    }

    // ---------------------------------------------------------------------------------------
    // A search returns a summary: the projection narrows the response, and only the response
    // ---------------------------------------------------------------------------------------

    /**
     * The contract here permits every field, so the columns missing from the statement are missing
     * because a search does not return them - not because policy withheld them. As with masking,
     * the guarantee is "never read" rather than "stripped afterwards".
     */
    @Test
    void discover_summaryProjection_selectsOnlyWhatASearchReturns() {
        databaseReturns(0, List.of());

        service().discover(null, decision(true, permissiveDetails()));

        assertThat(selectList(capturedQuery(repository)))
                .contains("p.id")
                .contains("p.name")
                .contains("p.description")
                .contains("pt.name")
                // whose product it is: a caller choosing which result to open needs it, and both
                // columns come from a join the search already makes
                .contains("o.organisation_key")
                .contains("o.name")
                .doesNotContain("p.topic")
                .doesNotContain("p.source")
                .doesNotContain("pr.name")
                .doesNotContain("pr.description")
                .doesNotContain("pr.active");
    }

    /**
     * The response is assembled to the same projection the query was built for, so the service
     * cannot quietly ask the assembler for more of a product than it selected - and the assembler
     * runs no block or attribute query for a block the search does not carry.
     */
    @Test
    void discover_page_isAssembledToTheSummaryProjection() {
        databaseReturns(1, List.of(product(3, "FloodRiskMapZones")));

        service().discover(null, decision(true, permissiveDetails()));

        assertThat(capturedProjection()).isEqualTo(ProductProjection.SUMMARY);
    }

    /**
     * The important half of the projection's meaning: it is about the response, not about what may
     * be searched. A caller may still filter and sort on fields a search does not return, and the
     * policy's row filter may still name one - all three reach the statement, none reach the
     * {@code SELECT} list.
     */
    @Test
    void discover_summaryProjection_narrowsNeitherTheRowFilterNorTheCallersFiltersAndSorts() {
        databaseReturns(0, List.of());
        ProductDiscoveryPolicyDecisionDetails details = details(
                """
                {"row_filter": {"type": "comparison", "field": "source",
                                "operator": "eq", "values": ["cadastre"]},
                 "allowed_filtered_fields": ["organisation.key", "topic"],
                 "visible_fields": ["name", "topic", "type", "source", "description",
                                    "organisation", "producer"],
                 "max_page_size": 20}""");

        service()
                .discover(
                        ProductDiscoveryRequestDTO.builder()
                                .filters(List.of(ProductDiscoveryFilterDTO.builder()
                                        .scope(FilterScope.ORGANISATION)
                                        .field("key")
                                        .values(List.of("ENV"))
                                        .build()))
                                .sort(List.of(ProductDiscoverySortDTO.builder()
                                        .field("topic")
                                        .direction("desc")
                                        .build()))
                                .build(),
                        decision(true, details));

        ProductSearchQuery query = capturedQuery(repository);
        assertThat(query.pageSql())
                .contains("p.source") // the policy's row filter
                .contains("o.organisation_key") // the caller's filter
                .contains("LOWER(p.topic) DESC"); // the caller's sort key
        // Filtered and sorted on, but not returned: p.source and p.topic are not in the summary.
        // organisation.key is, so the caller's filter on it and its presence in the result are
        // independent facts that happen to coincide here.
        assertThat(selectList(query)).doesNotContain("p.source").doesNotContain("p.topic");
    }

    /** Paging is the contract's and the caller's; the projection has no say in it. */
    @Test
    void discover_summaryProjection_leavesPagingToTheContract() {
        databaseReturns(7, List.of(product(1, "A")));

        ProductDiscoveryResponseDTO response = service()
                .discover(
                        ProductDiscoveryRequestDTO.builder().page(1).size(5).build(),
                        decision(true, permissiveDetails()));

        assertThat(response.page().size()).isEqualTo(5);
        assertThat(response.page().totalElements()).isEqualTo(7);
        assertThat(capturedQuery(repository).pageSql()).containsPattern("LIMIT :p\\d+ OFFSET :p\\d+");
    }

    @Test
    void discover_criteria_reachTheQueryAsBoundParametersNotText() {
        databaseReturns(0, List.of());

        service()
                .discover(
                        ProductDiscoveryRequestDTO.builder().text("O'Brien%").build(),
                        decision(true, permissiveDetails()));

        ProductSearchQuery query = capturedQuery(repository);
        assertThat(query.pageSql()).doesNotContain("O'Brien");
        assertThat(query.parameters()).containsValue("%o'brien\\%%");
    }

    /** Criteria are AND-ed with the policy's own condition; they can only ever narrow it. */
    @Test
    void discover_callerCriteria_areAndedWithThePolicyCondition() {
        databaseReturns(0, List.of());

        service()
                .discover(
                        ProductDiscoveryRequestDTO.builder()
                                .filters(List.of(ProductDiscoveryFilterDTO.builder()
                                        .field("type")
                                        .values(List.of("topic"))
                                        .build()))
                                .build(),
                        decision(true, permissiveDetails()));

        assertThat(capturedQuery(repository).pageSql())
                .containsPattern("WHERE \\(.*\\) AND \\(.*\\) AND \\(.*\\)")
                .doesNotContain(" OR (");
    }

    @Test
    void discover_defaultSort_isByNameWithTheIdAsTieBreak() {
        databaseReturns(0, List.of());

        service().discover(null, decision(true, permissiveDetails()));

        assertThat(capturedQuery(repository).pageSql()).contains("ORDER BY LOWER(p.name) ASC NULLS LAST, p.id ASC");
    }

    /** A criteria object the parser accepts is the only thing the query is built from. */
    @Test
    void discover_emptyRequestAndNullRequest_areTheSameSearch() {
        databaseReturns(0, List.of());
        ProductDiscoveryServiceImpl service = service();

        ProductDiscoveryResponseDTO fromNull = service.discover(null, decision(true, permissiveDetails()));
        ProductDiscoveryResponseDTO fromEmpty =
                service.discover(ProductDiscoveryRequestDTO.EMPTY, decision(true, permissiveDetails()));

        assertThat(fromEmpty.page()).isEqualTo(fromNull.page());
    }

    @Test
    void discover_criteriaFactory_isGivenTheContractItMustCheckAgainst() {
        ProductSearchCriteriaFactory factory = new ProductSearchCriteriaFactory();

        ProductSearchCriteria criteria = factory.create(null, ProductSearchContract.open(), Set.of());

        assertThat(criteria.page()).isZero();
        assertThat(criteria.size()).isEqualTo(20);
        assertThat(criteria.hasText()).isFalse();
    }
}
