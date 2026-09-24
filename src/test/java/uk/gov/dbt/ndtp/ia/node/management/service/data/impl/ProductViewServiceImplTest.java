/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;
import uk.gov.dbt.ndtp.ia.node.management.exception.AccessRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.DiscoveredProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductViewPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.DiscoveredProductAssembler;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.DiscoverySchema;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductProjection;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductQueryPlanner;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchContract;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchQuery;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchQueryBuilder;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyProvenance;

/**
 * Covers reading one product as a search constrained to it.
 *
 * <p>The query builder and its compiler are real rather than mocked. The claim this endpoint rests
 * on is that the policy's row filter reaches the {@code WHERE} clause and that the requested id can
 * only narrow it - a claim about the SQL, which a stubbed builder would assert nothing about. Only
 * the database and the assembly of rows are stubbed.
 *
 * <p>Two properties here are larger than the code they cover:
 *
 * <ul>
 *   <li>a product the row filter excludes is answered exactly as a product that does not exist, so
 *       an id cannot be used to probe what the caller may not discover;
 *   <li>a missing decision while policy is <em>on</em> refuses rather than reading unrestricted.
 * </ul>
 *
 * <p>This endpoint reads a {@link ProductProjection#FULL} product - the whole object model, as far
 * as the decision permits - which is the one thing that differs from a search. Nothing else here is
 * affected by the projection: the decision still decides what may be seen.
 */
@ExtendWith(MockitoExtension.class)
class ProductViewServiceImplTest {

    private static final long PRODUCT_ID = 3L;

    @Mock
    private ProductDiscoveryRepository repository;

    @Mock
    private DiscoveredProductAssembler assembler;

    private final ProductSearchQueryBuilder queryBuilder = new ProductSearchQueryBuilder(new DiscoverySchema("mn"));

    private ListAppender<ILoggingEvent> appender;
    private Logger serviceLogger;
    private Level originalLevel;

    @BeforeEach
    void captureTheServiceLog() {
        serviceLogger = (Logger) LoggerFactory.getLogger(ProductViewServiceImpl.class);
        originalLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void releaseTheServiceLog() {
        serviceLogger.detachAppender(appender);
        serviceLogger.setLevel(originalLevel);
    }

    /** The service as configured with policy enforcement on - the normal case. */
    private ProductViewServiceImpl service() {
        return service(true);
    }

    /**
     * @param policyEnforcementEnabled the {@code application.opa.enabled} master switch, which is
     *     what tells "no decision because policy is off" from "no decision although it is on"
     */
    private ProductViewServiceImpl service(boolean policyEnforcementEnabled) {
        ProductQueryPlanner planner =
                new ProductQueryPlanner(queryBuilder, repository, opaProperties(policyEnforcementEnabled));
        return new ProductViewServiceImpl(planner, repository, assembler);
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
    // Decisions, stated as the rule writes them
    // ---------------------------------------------------------------------------------------

    private static Optional<PolicyDecision<ProductViewPolicyDecisionDetails>> decision(String detailsJson) {
        return decision(true, detailsJson);
    }

    private static Optional<PolicyDecision<ProductViewPolicyDecisionDetails>> decision(
            boolean allow, String detailsJson) {
        return Optional.of(new PolicyDecision<>(
                allow,
                List.of(),
                new PolicyProvenance("product.view", "policies.product.view/2.0.0", "exact"),
                details(detailsJson)));
    }

    private static ProductViewPolicyDecisionDetails details(String json) {
        try {
            return new ObjectMapper().readValue(json, ProductViewPolicyDecisionDetails.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Test details are not valid JSON", e);
        }
    }

    /** A decision that lets this caller see every product, with nothing withheld. */
    private static Optional<PolicyDecision<ProductViewPolicyDecisionDetails>> permissiveDecision() {
        return decision(
                """
                {"access_level": "full",
                 "row_filter": {"type": "literal", "value": true},
                 "visible_fields": ["name", "topic", "type", "source", "description",
                                    "organisation", "producer", "consumers", "subscribedBy"],
                 "mask_sensitive_attributes": false,
                 "obligations": []}""");
    }

    /** A decision restricting the caller to the products their own organisation owns. */
    private static Optional<PolicyDecision<ProductViewPolicyDecisionDetails>> ownOrganisationDecision() {
        return decision(
                """
                {"access_level": "summary",
                 "row_filter": {"type": "comparison", "field": "organisation.key",
                                "operator": "eq", "values": ["ENV"]},
                 "visible_fields": ["name", "organisation"],
                 "masked_filtered_fields": ["source"],
                 "obligations": ["mask_response"]}""");
    }

    // ---------------------------------------------------------------------------------------
    // Stubs
    // ---------------------------------------------------------------------------------------

    private void databaseReturns(DiscoveredProductDTO... products) {
        List<Map<String, Object>> rows =
                products.length == 0 ? List.of() : List.of(Map.of(ProductSearchQuery.ID, (Object) PRODUCT_ID));
        when(repository.findPage(any())).thenReturn(rows);
        if (products.length > 0) {
            when(assembler.assemble(any(), any(), any())).thenReturn(List.of(products));
        }
    }

    private static DiscoveredProductDTO product(long id, String name) {
        return DiscoveredProductDTO.builder().id(id).name(name).build();
    }

    private ProductSearchQuery capturedQuery() {
        ArgumentCaptor<ProductSearchQuery> query = ArgumentCaptor.forClass(ProductSearchQuery.class);
        verify(repository).findPage(query.capture());
        return query.getValue();
    }

    /**
     * Just the {@code SELECT} list of the page statement - what the read actually fetches, apart
     * from the {@code WHERE} and {@code ORDER BY} a column may also appear in.
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

    private List<String> auditLines() {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("Product view audit"))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // ---------------------------------------------------------------------------------------
    // The product is found
    // ---------------------------------------------------------------------------------------

    @Test
    void view_productTheRowFilterAdmits_isReturned() {
        databaseReturns(product(PRODUCT_ID, "FloodRiskMapZones"));

        Optional<DiscoveredProductDTO> product = service().view(PRODUCT_ID, ownOrganisationDecision());

        assertThat(product).get().extracting(DiscoveredProductDTO::name).isEqualTo("FloodRiskMapZones");
    }

    /**
     * The whole of the endpoint's reuse claim in one assertion: the policy's predicate is the
     * {@code WHERE} clause, and the requested id is one more condition AND-ed onto it. The id can
     * therefore only ever narrow what policy allows - there is no branch in which it replaces it.
     */
    @Test
    void view_query_andsTheRequestedIdOntoThePolicysRowFilter() {
        databaseReturns(product(PRODUCT_ID, "FloodRiskMapZones"));

        service().view(PRODUCT_ID, ownOrganisationDecision());

        String sql = capturedQuery().pageSql();
        assertThat(sql)
                .contains("LOWER(o.organisation_key) IN (:")
                .contains("p.id IN (:")
                .containsPattern("WHERE \\(LOWER\\(o\\.organisation_key\\) IN \\(:p\\d+\\)\\) AND \\(");
    }

    @Test
    void view_requestedId_reachesTheQueryAsABoundParameterNotAsText() {
        databaseReturns(product(PRODUCT_ID, "FloodRiskMapZones"));

        service().view(PRODUCT_ID, permissiveDecision());

        ProductSearchQuery query = capturedQuery();
        // The id appears as a placeholder, never concatenated into the statement.
        assertThat(query.pageSql()).contains("p.id IN (:").doesNotContain("p.id IN (3)");
        assertThat(query.parameters().values()).anySatisfy(value -> assertThat(value)
                .asInstanceOf(InstanceOfAssertFactories.collection(BigDecimal.class))
                .containsExactly(BigDecimal.valueOf(PRODUCT_ID)));
    }

    @Test
    void view_query_asksForOneRow() {
        databaseReturns(product(PRODUCT_ID, "FloodRiskMapZones"));

        service().view(PRODUCT_ID, permissiveDecision());

        ProductSearchQuery query = capturedQuery();
        assertThat(query.pageSql()).containsPattern("LIMIT :p\\d+ OFFSET :p\\d+");
        assertThat(query.parameters()).containsValue(1).containsValue(0L);
    }

    @Test
    void view_maskedField_isNeverSelected() {
        databaseReturns(product(PRODUCT_ID, "FloodRiskMapZones"));

        service().view(PRODUCT_ID, ownOrganisationDecision());

        // The guarantee is not "stripped afterwards" but "never read".
        assertThat(capturedQuery().pageSql()).contains("p.name").doesNotContain("p.source");
    }

    @Test
    void view_product_isAssembledUnderTheSameContractTheQueryWasBuiltFrom() {
        databaseReturns(product(PRODUCT_ID, "FloodRiskMapZones"));

        service().view(PRODUCT_ID, ownOrganisationDecision());

        ArgumentCaptor<ProductSearchContract> contract = ArgumentCaptor.forClass(ProductSearchContract.class);
        verify(assembler).assemble(any(), contract.capture(), any());
        assertThat(contract.getValue().isEnforced()).isTrue();
        assertThat(contract.getValue().rowFilter())
                .isEqualTo(decisionRowFilter(ownOrganisationDecision()))
                .isInstanceOf(FilterNode.Comparison.class);
        assertThat(contract.getValue().isVisible("name")).isTrue();
        assertThat(contract.getValue().isVisible("source")).isFalse();
    }

    // ---------------------------------------------------------------------------------------
    // Reading one product returns the whole object model
    // ---------------------------------------------------------------------------------------

    /**
     * The response is assembled to the same projection the query was built for. Unlike a search,
     * which returns a summary, reading one product asks for everything the object model can carry -
     * subject to the decision, which is the only thing that withholds anything here.
     */
    @Test
    void view_product_isAssembledToTheFullProjection() {
        databaseReturns(product(PRODUCT_ID, "FloodRiskMapZones"));

        service().view(PRODUCT_ID, permissiveDecision());

        assertThat(capturedProjection()).isEqualTo(ProductProjection.FULL);
    }

    /**
     * Every field a permitting contract allows is selected, the blocks' members included. This is
     * what a search deliberately does not carry, and it is unchanged by the projection a search
     * uses.
     */
    @Test
    void view_fullProjection_selectsEveryFieldTheContractPermits() {
        databaseReturns(product(PRODUCT_ID, "FloodRiskMapZones"));

        service().view(PRODUCT_ID, permissiveDecision());

        assertThat(selectList(capturedQuery()))
                .contains("p.id")
                .contains("p.name")
                .contains("p.description")
                .contains("p.topic")
                .contains("pt.name")
                .contains("p.source")
                .contains("o.organisation_key")
                .contains("o.name")
                .contains("pr.name")
                .contains("pr.description")
                .contains("pr.active");
    }

    private static FilterNode decisionRowFilter(Optional<PolicyDecision<ProductViewPolicyDecisionDetails>> decision) {
        return decision.orElseThrow().details().rowFilter();
    }

    // ---------------------------------------------------------------------------------------
    // The product is not found - and the two ways of not finding it are the same answer
    // ---------------------------------------------------------------------------------------

    /**
     * The non-disclosure guarantee. A product the row filter excludes and a product that never
     * existed both come back as nothing, because the exclusion is part of the query rather than a
     * check applied to a loaded product. Telling the two apart would turn the endpoint into an
     * oracle for the existence of products the caller may not discover.
     */
    @Test
    void view_productTheRowFilterExcludes_isIndistinguishableFromOneThatDoesNotExist() {
        databaseReturns();
        ProductViewServiceImpl service = service();

        Optional<DiscoveredProductDTO> excluded = service.view(PRODUCT_ID, ownOrganisationDecision());
        Optional<DiscoveredProductDTO> neverExisted = service.view(999_999L, ownOrganisationDecision());

        assertThat(excluded).isEmpty();
        assertThat(neverExisted).isEmpty();
        assertThat(excluded).isEqualTo(neverExisted);
        // Neither id causes anything further to be read, so the two cost the same as well as
        // reading the same - there is no timing or query difference to tell them apart by.
        verifyNoInteractions(assembler);
    }

    @Test
    void view_deniedDecision_findsNothing() {
        databaseReturns();

        Optional<DiscoveredProductDTO> product = service().view(PRODUCT_ID, decision(false, "{}"));

        // A denied request never reaches the query; should one, its row filter matches no product.
        assertThat(capturedQuery().pageSql()).contains("WHERE (1 = 0)");
        assertThat(product).isEmpty();
    }

    @Test
    void view_decisionWithoutARowFilter_findsNothing() {
        databaseReturns();

        // The withholding default: a rule that says nothing about which products exist for this
        // caller has said "none", not "all".
        assertThat(service().view(PRODUCT_ID, decision("{\"access_level\": \"full\"}")))
                .isEmpty();
        assertThat(capturedQuery().pageSql()).contains("WHERE (1 = 0)");
    }

    // ---------------------------------------------------------------------------------------
    // A view is not a search: nothing is counted
    // ---------------------------------------------------------------------------------------

    @Test
    void view_anyOutcome_runsNoCountQuery() {
        databaseReturns(product(PRODUCT_ID, "FloodRiskMapZones"));

        service().view(PRODUCT_ID, permissiveDecision());

        // The caller asked for a product, not for how many match.
        verify(repository, never()).count(any());
    }

    @Test
    void view_productNotFound_runsNoCountQuery() {
        databaseReturns();

        service().view(PRODUCT_ID, permissiveDecision());

        verify(repository, never()).count(any());
    }

    // ---------------------------------------------------------------------------------------
    // Policy switched off
    // ---------------------------------------------------------------------------------------

    @Test
    void view_policySwitchedOff_returnsTheProductUnrestricted() {
        databaseReturns(product(PRODUCT_ID, "FloodRiskMapZones"));

        Optional<DiscoveredProductDTO> product = service(false).view(PRODUCT_ID, Optional.empty());

        assertThat(product).get().extracting(DiscoveredProductDTO::name).isEqualTo("FloodRiskMapZones");
        // The open contract's row filter: every product qualifies, and only the id narrows it.
        assertThat(capturedQuery().pageSql()).contains("WHERE (1 = 1)").contains("p.id IN (:");
    }

    @Test
    void view_policySwitchedOff_withholdsNothing() {
        databaseReturns(product(PRODUCT_ID, "FloodRiskMapZones"));

        service(false).view(PRODUCT_ID, Optional.empty());

        ArgumentCaptor<ProductSearchContract> contract = ArgumentCaptor.forClass(ProductSearchContract.class);
        verify(assembler).assemble(any(), contract.capture(), any());
        assertThat(contract.getValue().isEnforced()).isFalse();
        assertThat(contract.getValue().masksSensitiveAttributes()).isFalse();
        assertThat(contract.getValue().isVisible("source")).isTrue();
    }

    // ---------------------------------------------------------------------------------------
    // Fail closed
    // ---------------------------------------------------------------------------------------

    /**
     * The quiet failure this guards against: policy is switched on, the enforcement point does not
     * run, and the service treats the missing decision as "policy is off" - handing over a product
     * nobody authorised, in a response that looks entirely normal. "No decision" only means
     * "unrestricted" when policy was never meant to judge the request.
     */
    @Test
    void view_policyOnButNoDecisionReachedTheHandler_refusesAndReadsNothing() {
        var service = service(true);
        assertThatThrownBy(() -> service.view(PRODUCT_ID, Optional.empty()))
                .isInstanceOf(AccessRejectedException.class)
                .hasMessage("Access denied by policy")
                .extracting(e -> ((AccessRejectedException) e).getReasons())
                .isEqualTo(List.of(ProductQueryPlanner.REASON_ENFORCEMENT_MISSING));

        verifyNoInteractions(repository);
        verifyNoInteractions(assembler);
    }

    @Test
    void view_obligationTheServiceCannotFulfil_refusesTheRead() {
        Optional<PolicyDecision<ProductViewPolicyDecisionDetails>> decision = decision(
                """
                {"row_filter": {"type": "literal", "value": true},
                 "visible_fields": ["name"],
                 "obligations": ["audit_access", "encrypt_at_rest"]}""");

        var service = service();
        assertThatThrownBy(() -> service.view(PRODUCT_ID, decision))
                .isInstanceOf(AccessRejectedException.class)
                .hasMessage("Access denied by policy")
                .extracting(e -> ((AccessRejectedException) e).getReasons())
                // Logged but never returned, so a caller learns nothing about how policy is wired.
                .isEqualTo(List.of(ProductQueryPlanner.REASON_OBLIGATION_UNSUPPORTED));

        verifyNoInteractions(repository);
        verifyNoInteractions(assembler);
    }

    @Test
    void view_rowFilterThatCannotBeCompiled_refusesTheRead() {
        Optional<PolicyDecision<ProductViewPolicyDecisionDetails>> decision = decision(
                """
                {"row_filter": {"type": "comparison", "field": "no_such_field",
                                "operator": "eq", "values": ["x"]},
                 "visible_fields": ["name"]}""");

        var service = service();
        assertThatThrownBy(() -> service.view(PRODUCT_ID, decision))
                .isInstanceOf(AccessRejectedException.class)
                .extracting(e -> ((AccessRejectedException) e).getReasons())
                .isEqualTo(List.of(ProductQueryPlanner.REASON_ROW_FILTER_UNCOMPILABLE));

        verify(repository, never()).findPage(any());
        verifyNoInteractions(assembler);
    }

    // ---------------------------------------------------------------------------------------
    // Obligations the service can fulfil
    // ---------------------------------------------------------------------------------------

    @Test
    void view_auditAccessObliged_writesOneAuditRecord() {
        databaseReturns(product(PRODUCT_ID, "FloodRiskMapZones"));

        service()
                .view(
                        PRODUCT_ID,
                        decision(
                                """
                        {"access_level": "full",
                         "row_filter": {"type": "literal", "value": true},
                         "visible_fields": ["name"],
                         "obligations": ["audit_access"]}"""));

        assertThat(auditLines())
                .singleElement()
                .asString()
                .contains("productId=3")
                .contains("found=true")
                .contains("accessLevel=full")
                .contains("product.view");
    }

    @Test
    void view_auditAccessObliged_recordsThatNothingWasFound() {
        databaseReturns();

        service()
                .view(
                        PRODUCT_ID,
                        decision(
                                """
                        {"row_filter": {"type": "literal", "value": true},
                         "visible_fields": ["name"],
                         "obligations": ["audit_access"]}"""));

        assertThat(auditLines()).singleElement().asString().contains("found=false");
    }

    @Test
    void view_auditAccessNotObliged_writesNoAuditRecord() {
        databaseReturns(product(PRODUCT_ID, "FloodRiskMapZones"));

        service().view(PRODUCT_ID, permissiveDecision());

        assertThat(auditLines()).isEmpty();
    }

    @Test
    void view_policySwitchedOff_writesNoAuditRecord() {
        databaseReturns(product(PRODUCT_ID, "FloodRiskMapZones"));

        service(false).view(PRODUCT_ID, Optional.empty());

        // The open contract obliges nothing, so there is nothing to audit.
        assertThat(auditLines()).isEmpty();
    }

    @Test
    void view_obligationsTheServiceKnows_areAccepted() {
        databaseReturns(product(PRODUCT_ID, "FloodRiskMapZones"));

        Optional<DiscoveredProductDTO> product = service()
                .view(
                        PRODUCT_ID,
                        decision(
                                """
                        {"row_filter": {"type": "literal", "value": true},
                         "visible_fields": ["name"],
                         "obligations": ["audit_access", "mask_response", "aggregate_before_release"]}"""));

        assertThat(product).isPresent();
    }
}
