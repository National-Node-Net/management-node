/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;
import uk.gov.dbt.ndtp.ia.node.management.exception.AccessRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductPolicyContractDetails;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductViewPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeScopeCode;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyProvenance;

/**
 * The single place where a policy decision becomes a query, for every endpoint that reads products.
 * Getting any of it wrong discloses products nobody authorised, in a response that looks entirely
 * normal, so each refusal is asserted on its own.
 *
 * <p>Three of these tests are the ones that must never regress:
 *
 * <ul>
 *   <li>a decision that did not arrive <em>while policy is on</em> refuses, rather than being read
 *       as "policy is off" and searching unrestricted;
 *   <li>an obligation the service cannot fulfil refuses, rather than being ignored;
 *   <li>a contract that will not compile refuses, rather than being skipped.
 * </ul>
 *
 * <p>The last test proves the point of the class existing at all: a {@code product.view} decision
 * yields its contract the same way a {@code product.discover} one does, so the two endpoints cannot
 * acquire separate ideas of what is withheld.
 */
@ExtendWith(MockitoExtension.class)
class ProductQueryPlannerTest {

    private static final int DEFAULT_MAX_PAGE_SIZE = ProductSearchContract.DEFAULT_MAX_PAGE_SIZE;

    private static final String WHAT = "Product discover";

    private static final String REFUSAL_MESSAGE = "Access denied by policy";

    @Mock
    private ProductDiscoveryRepository repository;

    private final ProductSearchQueryBuilder queryBuilder = new ProductSearchQueryBuilder(new DiscoverySchema("mn"));

    /** The planner as configured with policy enforcement on - the normal case. */
    private ProductQueryPlanner planner() {
        return planner(true);
    }

    /**
     * @param policyEnforcementEnabled the {@code application.opa.enabled} master switch, which is
     *     what tells "no decision because policy is off" from "no decision although it is on"
     */
    private ProductQueryPlanner planner(boolean policyEnforcementEnabled) {
        return new ProductQueryPlanner(queryBuilder, repository, opaProperties(policyEnforcementEnabled));
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
    // Decisions in the shape the rules return them
    // ---------------------------------------------------------------------------------------

    private static <D extends ProductPolicyContractDetails> Optional<PolicyDecision<D>> decision(
            boolean allow, D details) {
        return Optional.of(new PolicyDecision<>(
                allow,
                List.of(),
                new PolicyProvenance("product.discover", "policies.product.discover/3.0.0", "exact"),
                details));
    }

    /**
     * Details read through Jackson, so a test states the contract as a policy author would write
     * it rather than as Java happens to hold it.
     */
    private static <D extends ProductPolicyContractDetails> D details(String json, Class<D> type) {
        try {
            return new ObjectMapper().readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Test details are not valid JSON", e);
        }
    }

    private static ProductDiscoveryPolicyDecisionDetails details(String json) {
        return details(json, ProductDiscoveryPolicyDecisionDetails.class);
    }

    private static final String SEARCH_CONTRACT_JSON =
            """
            {"evaluation": "request",
             "row_filter": {"type": "comparison", "attribute": "identifiability",
                            "operator": "in", "values": ["non_personal"]},
             "allowed_filtered_fields": ["name"],
             "allowed_filtered_attributes": ["identifiability"],
             "masked_filtered_fields": ["source"],
             "masked_filtered_attributes": ["population_risk_tags"],
             "visible_fields": ["name", "topic"],
             "text_search_fields": ["name"],
             "mask_sensitive_attributes": false,
             "max_page_size": 25,
             "obligations": ["audit_access", "mask_response"]}""";

    private static ProductSearchCriteria noCriteria(ProductSearchContract contract) {
        return new ProductSearchCriteriaFactory().create(null, contract, Set.of());
    }

    // ---------------------------------------------------------------------------------------
    // contractFor
    // ---------------------------------------------------------------------------------------

    @Test
    void contractFor_decisionPresent_yieldsAnEnforcingContractCarryingWhatTheRuleGranted() {
        ProductSearchContract contract = planner().contractFor(WHAT, decision(true, details(SEARCH_CONTRACT_JSON)));

        assertThat(contract.isEnforced()).isTrue();
        assertThat(contract.rowFilter()).isInstanceOf(FilterNode.Comparison.class);
        assertThat(contract.maskedFields()).containsExactly("source");
        assertThat(contract.maskedAttributes()).containsExactly("population_risk_tags");
        assertThat(contract.maskedAttributeNames(PolicyAttributeScopeCode.PRODUCT))
                .containsExactly("population_risk_tags");
        assertThat(contract.filterableFields()).containsExactly("name");
        assertThat(contract.filterableAttributes()).containsExactly("identifiability");
        assertThat(contract.obligations()).containsExactly("audit_access", "mask_response");
        assertThat(contract.isVisible("name")).isTrue();
        // Visible to the rule, yet masked: masking wins, so it is never selected.
        assertThat(contract.isVisible("source")).isFalse();
    }

    @Test
    void contractFor_deniedDecision_yieldsADenyAllRowFilter() {
        ProductSearchContract contract = planner().contractFor(WHAT, decision(false, details(SEARCH_CONTRACT_JSON)));

        // A denied request never reaches the query; should one, it finds nothing.
        assertThat(contract.rowFilter()).isEqualTo(FilterNode.DENY_ALL);
        assertThat(contract.isEnforced()).isTrue();
    }

    @Test
    void contractFor_noDecisionAndPolicyOff_yieldsTheOpenContract() {
        ProductSearchContract contract = planner(false).contractFor(WHAT, Optional.empty());

        assertThat(contract.isEnforced()).isFalse();
        assertThat(contract.rowFilter()).isEqualTo(FilterNode.ALLOW_ALL);
        assertThat(contract.masksSensitiveAttributes()).isFalse();
        assertThat(contract.maxPageSize()).isEqualTo(DEFAULT_MAX_PAGE_SIZE);
        // Nothing is restricted: anything may be seen, and anything may be filtered on.
        assertThat(contract.isVisible("source")).isTrue();
        assertThat(contract.isMasked("organisation.name")).isFalse();
    }

    /**
     * The one that must never regress. Policy is switched on, the enforcement point did not run,
     * and no decision arrived. Reading that as "policy is off" would hand every product to a caller
     * nobody authorised, in a response indistinguishable from a working one.
     */
    @Test
    void contractFor_noDecisionAndPolicyOn_refusesWithEnforcementMissing() {
        var planner = planner(true);
        assertThatThrownBy(() -> planner.contractFor(WHAT, Optional.empty()))
                .isInstanceOf(AccessRejectedException.class)
                .hasMessage(REFUSAL_MESSAGE)
                .extracting(e -> ((AccessRejectedException) e).getReasons())
                .isEqualTo(List.of(ProductQueryPlanner.REASON_ENFORCEMENT_MISSING));
        verifyNoInteractions(repository);
    }

    @Test
    void contractFor_refusal_carriesAnErrorIdSoTheResponseCanBeTracedToTheLog() {
        var plannerEnforcing = planner(true);
        assertThatThrownBy(() -> plannerEnforcing.contractFor(WHAT, Optional.empty()))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(AccessRejectedException.class))
                .extracting(AccessRejectedException::getErrorId)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .isNotBlank();
    }

    // ---------------------------------------------------------------------------------------
    // Page size
    // ---------------------------------------------------------------------------------------

    @Test
    void contractFor_decisionSetsAMaxPageSize_usesTheDecisionsValue() {
        ProductSearchContract contract = planner().contractFor(WHAT, decision(true, details(SEARCH_CONTRACT_JSON)));

        assertThat(contract.maxPageSize()).isEqualTo(25);
    }

    @Test
    void contractFor_decisionSetsNoMaxPageSize_fallsBackToTheConfiguredDefault() {
        ProductDiscoveryPolicyDecisionDetails details =
                details("""
                {"row_filter": {"type": "literal", "value": true}}""");

        assertThat(planner().contractFor(WHAT, decision(true, details)).maxPageSize())
                .isEqualTo(DEFAULT_MAX_PAGE_SIZE);
    }

    @Test
    void contractFor_maxPageSizeOfZeroOrLess_fallsBackToTheConfiguredDefault() {
        ProductQueryPlanner planner = planner();

        ProductSearchContract zero = planner.contractFor(
                WHAT,
                decision(
                        true,
                        details(
                                """
                        {"row_filter": {"type": "literal", "value": true}, "max_page_size": 0}""")));
        ProductSearchContract negative = planner.contractFor(
                WHAT,
                decision(
                        true,
                        details(
                                """
                        {"row_filter": {"type": "literal", "value": true}, "max_page_size": -5}""")));

        assertThat(zero.maxPageSize()).isEqualTo(DEFAULT_MAX_PAGE_SIZE);
        assertThat(negative.maxPageSize()).isEqualTo(DEFAULT_MAX_PAGE_SIZE);
    }

    // ---------------------------------------------------------------------------------------
    // Obligations
    // ---------------------------------------------------------------------------------------

    @Test
    void contractFor_obligationsTheServiceKnows_areAccepted() {
        ProductDiscoveryPolicyDecisionDetails details = details(
                """
                {"row_filter": {"type": "literal", "value": true},
                 "obligations": ["audit_access", "mask_response", "aggregate_before_release"]}""");

        ProductSearchContract contract = planner().contractFor(WHAT, decision(true, details));

        assertThat(contract.obligations()).containsExactly("audit_access", "mask_response", "aggregate_before_release");
    }

    /** XACML's rule: a PEP that cannot fulfil an obligation must not grant access. */
    @Test
    void contractFor_obligationTheServiceCannotFulfil_refusesRatherThanIgnoringIt() {
        ProductDiscoveryPolicyDecisionDetails details = details(
                """
                {"row_filter": {"type": "literal", "value": true},
                 "obligations": ["audit_access", "encrypt_at_rest"]}""");

        var planner = planner();
        var taken = decision(true, details);
        assertThatThrownBy(() -> planner.contractFor(WHAT, taken))
                .isInstanceOf(AccessRejectedException.class)
                .hasMessage(REFUSAL_MESSAGE)
                .extracting(e -> ((AccessRejectedException) e).getReasons())
                .isEqualTo(List.of(ProductQueryPlanner.REASON_OBLIGATION_UNSUPPORTED));
        verifyNoInteractions(repository);
    }

    @Test
    void contractFor_noDecision_doesNotCheckObligations() {
        ProductQueryPlanner planner = planner(false);

        assertThatCode(() -> planner.contractFor(WHAT, Optional.empty())).doesNotThrowAnyException();
        // There is no decision to attach obligations, so there is nothing to refuse.
        assertThat(planner.contractFor(WHAT, Optional.empty()).obligations()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // Sensitive attribute names
    // ---------------------------------------------------------------------------------------

    @Test
    void sensitiveAttributeNames_contractMasksThem_consultsTheCatalogue() {
        when(repository.findSensitiveAttributeNames()).thenReturn(Set.of("population_risk_tags"));
        ProductSearchContract contract = planner()
                .contractFor(
                        WHAT,
                        decision(
                                true,
                                details(
                                        """
                                        {"row_filter": {"type": "literal", "value": true},
                                         "mask_sensitive_attributes": true}""")));

        assertThat(planner().sensitiveAttributeNames(contract)).containsExactly("population_risk_tags");
    }

    @Test
    void sensitiveAttributeNames_contractDoesNotMaskThem_returnsEmptyWithoutReadingTheCatalogue() {
        ProductQueryPlanner planner = planner();
        ProductSearchContract contract = planner.contractFor(WHAT, decision(true, details(SEARCH_CONTRACT_JSON)));

        assertThat(planner.sensitiveAttributeNames(contract)).isEmpty();
        verify(repository, never()).findSensitiveAttributeNames();
    }

    @Test
    void sensitiveAttributeNames_openContract_returnsEmptyWithoutReadingTheCatalogue() {
        ProductQueryPlanner planner = planner(false);

        assertThat(planner.sensitiveAttributeNames(planner.contractFor(WHAT, Optional.empty())))
                .isEmpty();
        verify(repository, never()).findSensitiveAttributeNames();
    }

    // ---------------------------------------------------------------------------------------
    // compile
    // ---------------------------------------------------------------------------------------

    @Test
    void compile_compilableContract_returnsTheStatementAndItsBoundParameters() {
        Optional<PolicyDecision<ProductDiscoveryPolicyDecisionDetails>> decision =
                decision(true, details(SEARCH_CONTRACT_JSON));
        ProductQueryPlanner planner = planner();
        ProductSearchContract contract = planner.contractFor(WHAT, decision);

        ProductSearchQuery query =
                planner.compile(WHAT, contract, noCriteria(contract), ProductProjection.FULL, decision);

        assertThat(query.pageSql()).contains("SELECT").contains("p.name");
        assertThat(query.countSql()).contains("SELECT");
        // The policy's condition is part of the statement, bound rather than interpolated.
        assertThat(query.parameters()).containsValue("identifiability");
    }

    /**
     * A row filter naming something this service cannot translate refuses the request - a policy
     * that cannot be applied is never skipped.
     */
    @Test
    void compile_rowFilterThatCannotBeCompiled_refusesWithAReasonTheCallerNeverSees() {
        Optional<PolicyDecision<ProductDiscoveryPolicyDecisionDetails>> decision = decision(
                true,
                details(
                        """
                        {"row_filter": {"type": "comparison", "field": "no_such_field",
                                        "operator": "eq", "values": ["x"]},
                         "visible_fields": ["name"]}"""));
        ProductQueryPlanner planner = planner();
        ProductSearchContract contract = planner.contractFor(WHAT, decision);
        ProductSearchCriteria criteria = noCriteria(contract);

        assertThatThrownBy(() -> planner.compile(WHAT, contract, criteria, ProductProjection.FULL, decision))
                .isInstanceOf(AccessRejectedException.class)
                // What the caller is told is only that they were refused.
                .hasMessage(REFUSAL_MESSAGE)
                .extracting(e -> ((AccessRejectedException) e).getReasons())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .containsExactly(ProductQueryPlanner.REASON_ROW_FILTER_UNCOMPILABLE)
                // Every reason is a "policy." one, and those are filtered out of what is returned:
                // how the rule is wired is nothing a caller can act on.
                .allSatisfy(reason -> assertThat(reason).startsWith("policy."));
    }

    /**
     * The projection is the endpoint's own narrowing, and the planner is only the place it passes
     * through: it neither overrides it nor drops it on the way to the builder.
     */
    @Test
    void compile_projection_reachesTheStatementItBuilds() {
        Optional<PolicyDecision<ProductDiscoveryPolicyDecisionDetails>> decision = decision(
                true,
                details(
                        """
                        {"row_filter": {"type": "literal", "value": true},
                         "visible_fields": ["name", "description", "type", "source"]}"""));
        ProductQueryPlanner planner = planner();
        ProductSearchContract contract = planner.contractFor(WHAT, decision);
        ProductSearchCriteria criteria = noCriteria(contract);

        ProductSearchQuery summary = planner.compile(WHAT, contract, criteria, ProductProjection.SUMMARY, decision);
        ProductSearchQuery full = planner.compile(WHAT, contract, criteria, ProductProjection.FULL, decision);

        assertThat(summary.pageSql())
                .contains("p.name AS name", "pt.name AS type")
                .doesNotContain("p.source");
        assertThat(full.pageSql()).contains("p.source AS source");
        // Only the columns narrow: the same contract yields the same condition either way.
        assertThat(summary.countSql()).isEqualTo(full.countSql());
    }

    // ---------------------------------------------------------------------------------------
    // Provenance
    // ---------------------------------------------------------------------------------------

    @Test
    void provenance_decisionPresent_namesTheRuleThatAnswered() {
        assertThat(ProductQueryPlanner.provenance(decision(true, details(SEARCH_CONTRACT_JSON))))
                .isEqualTo(Map.of("id", "product.discover", "version", "policies.product.discover/3.0.0"));
    }

    @Test
    void provenance_noDecision_isEmpty() {
        assertThat(ProductQueryPlanner.provenance(Optional.empty())).isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // The same planner serves the view endpoint
    // ---------------------------------------------------------------------------------------

    /**
     * Reading one product is a search constrained to that product, so a {@code product.view}
     * decision must yield its contract exactly as a {@code product.discover} one does. This is the
     * whole reason the planner is shared: a second endpoint cannot acquire its own idea of masking.
     */
    @Test
    void contractFor_viewDecision_yieldsTheContractTheSameWay() {
        ProductViewPolicyDecisionDetails view = details(
                """
                {"access_level": "read",
                 "row_filter": {"type": "comparison", "attribute": "identifiability",
                                "operator": "in", "values": ["non_personal"]},
                 "visible_fields": ["name", "topic"],
                 "masked_filtered_fields": ["source"],
                 "masked_filtered_attributes": ["population_risk_tags"],
                 "mask_sensitive_attributes": false,
                 "obligations": ["audit_access"]}""",
                ProductViewPolicyDecisionDetails.class);

        ProductSearchContract contract = planner().contractFor("Product view", decision(true, view));

        assertThat(contract.isEnforced()).isTrue();
        assertThat(contract.rowFilter()).isInstanceOf(FilterNode.Comparison.class);
        assertThat(contract.isVisible("name")).isTrue();
        assertThat(contract.isVisible("source")).isFalse();
        assertThat(contract.maskedFields()).containsExactly("source");
        assertThat(contract.maskedAttributes()).containsExactly("population_risk_tags");
        assertThat(contract.obligations()).containsExactly("audit_access");
        assertThat(contract.masksSensitiveAttributes()).isFalse();
        // Reading one product has no criteria and no page, so a view decision sets no search terms:
        // it permits none, and the page limit falls back to the configured default.
        assertThat(contract.filterableFields()).isEmpty();
        assertThat(contract.filterableAttributes()).isEmpty();
        assertThat(contract.textSearchFields()).isEmpty();
        assertThat(contract.maxPageSize()).isEqualTo(DEFAULT_MAX_PAGE_SIZE);
    }

    @Test
    void compile_viewDecision_compilesTheSameWayAsASearch() {
        Optional<PolicyDecision<ProductViewPolicyDecisionDetails>> decision = decision(
                true,
                details(
                        """
                        {"access_level": "read",
                         "row_filter": {"type": "comparison", "field": "name",
                                        "operator": "eq", "values": ["FloodRiskMapZones"]},
                         "visible_fields": ["name"]}""",
                        ProductViewPolicyDecisionDetails.class));
        ProductQueryPlanner planner = planner();
        ProductSearchContract contract = planner.contractFor("Product view", decision);

        ProductSearchQuery query =
                planner.compile("Product view", contract, noCriteria(contract), ProductProjection.FULL, decision);

        // Field comparisons are case-folded and bound, never interpolated.
        assertThat(query.pageSql()).contains("LOWER(p.name) IN (").doesNotContain("FloodRiskMapZones");
        assertThat(query.parameters().values()).anySatisfy(value -> assertThat(value)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.collection(String.class))
                .containsExactly("floodriskmapzones"));
    }
}
