/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterTarget;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductViewPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeScopeCode;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyProvenance;

/**
 * Covers what a {@code product.discover} decision means for one search, and what the absence of a
 * decision means: the open contract enforces nothing, and an enforcing one withholds anything the
 * decision does not grant.
 */
class ProductSearchContractTest {

    private static final int DEFAULT_MAX_PAGE_SIZE = ProductSearchContract.DEFAULT_MAX_PAGE_SIZE;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void open_enforcesNothing() {
        ProductSearchContract contract = ProductSearchContract.open();

        assertThat(contract.isEnforced()).isFalse();
        assertThat(contract.rowFilter()).isEqualTo(FilterNode.ALLOW_ALL);
        assertThat(contract.mayFilterOn(FilterTarget.ofField("anything"))).isTrue();
        assertThat(contract.mayFilterOn(FilterTarget.ofAttribute("anything"))).isTrue();
        assertThat(contract.mayFilterOn(new FilterTarget(FilterScope.ORGANISATION, true, "key")))
                .isTrue();
        assertThat(contract.isVisible("anything")).isTrue();
        assertThat(contract.isVisible(ProductBlock.CONSUMERS.apiName())).isTrue();
        assertThat(contract.isMasked("organisation.name")).isFalse();
        assertThat(contract.masksSensitiveAttributes()).isFalse();
        assertThat(contract.maxPageSize()).isEqualTo(DEFAULT_MAX_PAGE_SIZE);
        assertThat(contract.unmaskRules()).isEmpty();
        assertThat(contract.obligations()).isEmpty();
        assertThat(contract.maskedAttributeNames(PolicyAttributeScopeCode.PRODUCT))
                .isEmpty();
    }

    @Test
    void textSearchFields_open_isEveryTextSearchableField() {
        ProductSearchContract contract = ProductSearchContract.open();

        assertThat(contract.textSearchFields())
                .containsExactlyElementsOf(List.of(ProductField.NAME, ProductField.DESCRIPTION))
                .allMatch(ProductField::isTextSearchable);
    }

    @Test
    void mayFilterOn_enforcing_allowsOnlyWhatTheDecisionAllows() throws JsonProcessingException {
        ProductSearchContract contract = enforcing(
                true,
                """
                {"allowed_filtered_fields": ["name", "organisation.key"],
                 "allowed_filtered_attributes": ["identifiability"]}""");

        assertThat(contract.isEnforced()).isTrue();
        assertThat(contract.mayFilterOn(FilterTarget.ofField("name"))).isTrue();
        assertThat(contract.mayFilterOn(new FilterTarget(FilterScope.ORGANISATION, true, "key")))
                .isTrue();
        assertThat(contract.mayFilterOn(FilterTarget.ofField("topic"))).isFalse();
        assertThat(contract.mayFilterOn(FilterTarget.ofAttribute("identifiability")))
                .isTrue();
        assertThat(contract.mayFilterOn(FilterTarget.ofAttribute("population_risk_tags")))
                .isFalse();
        assertThat(contract.filterableFields()).containsExactly("name", "organisation.key");
        assertThat(contract.filterableAttributes()).containsExactly("identifiability");
    }

    @Test
    void mayFilterOn_maskedField_isRefusedEvenWhenAllowed() throws JsonProcessingException {
        ProductSearchContract contract = enforcing(
                true,
                """
                {"allowed_filtered_fields": ["name", "topic"],
                 "masked_filtered_fields": ["topic"]}""");

        assertThat(contract.mayFilterOn(FilterTarget.ofField("name"))).isTrue();
        assertThat(contract.mayFilterOn(FilterTarget.ofField("topic"))).isFalse();
        assertThat(contract.maskedFields()).containsExactly("topic");
    }

    @Test
    void mayFilterOn_maskedAttribute_isRefusedEvenWhenAllowed() throws JsonProcessingException {
        ProductSearchContract contract = enforcing(
                true,
                """
                {"allowed_filtered_attributes": ["identifiability", "population_risk_tags"],
                 "masked_filtered_attributes": ["population_risk_tags"]}""");

        assertThat(contract.mayFilterOn(FilterTarget.ofAttribute("identifiability")))
                .isTrue();
        assertThat(contract.mayFilterOn(FilterTarget.ofAttribute("population_risk_tags")))
                .isFalse();
        assertThat(contract.maskedAttributes()).containsExactly("population_risk_tags");
    }

    @Test
    void isVisible_requiresVisibleAndNotMasked() throws JsonProcessingException {
        ProductSearchContract contract = enforcing(
                true,
                """
                {"visible_fields": ["name", "topic", "organisation", "consumers"],
                 "masked_filtered_fields": ["topic", "consumers"]}""");

        assertThat(contract.isVisible("name")).isTrue();
        assertThat(contract.isVisible("topic")).isFalse();
        assertThat(contract.isVisible("source")).isFalse();
        assertThat(contract.isVisible(ProductBlock.ORGANISATION.apiName())).isTrue();
        assertThat(contract.isVisible(ProductBlock.CONSUMERS.apiName())).isFalse();
        assertThat(contract.isVisible(ProductBlock.SUBSCRIBED_BY.apiName())).isFalse();
        assertThat(contract.isMasked("topic")).isTrue();
        assertThat(contract.isMasked("name")).isFalse();
    }

    @Test
    void rowFilter_deniedDecision_deniesEveryProduct() throws JsonProcessingException {
        ProductSearchContract contract =
                enforcing(false, """
                {"row_filter": {"type": "literal", "value": true}}""");

        assertThat(contract.rowFilter()).isEqualTo(FilterNode.DENY_ALL);
    }

    @Test
    void rowFilter_enforcingDecisionWithoutOne_deniesEveryProduct() throws JsonProcessingException {
        ProductSearchContract contract = enforcing(true, "{}");

        assertThat(contract.rowFilter()).isEqualTo(FilterNode.DENY_ALL);
    }

    @Test
    void rowFilter_enforcingDecisionWithOne_isTheDecisions() throws JsonProcessingException {
        ProductSearchContract contract =
                enforcing(true, """
                {"row_filter": {"type": "literal", "value": true}}""");

        assertThat(contract.rowFilter()).isEqualTo(FilterNode.ALLOW_ALL);
    }

    @Test
    void maxPageSize_decidedValue_wins() throws JsonProcessingException {
        assertThat(enforcing(true, "{\"max_page_size\": 5}").maxPageSize()).isEqualTo(5);
    }

    @Test
    void maxPageSize_absentOrBelowOne_fallsBackToTheDefault() throws JsonProcessingException {
        assertThat(enforcing(true, "{}").maxPageSize()).isEqualTo(DEFAULT_MAX_PAGE_SIZE);
        assertThat(enforcing(true, "{\"max_page_size\": 0}").maxPageSize()).isEqualTo(DEFAULT_MAX_PAGE_SIZE);
        assertThat(enforcing(true, "{\"max_page_size\": -3}").maxPageSize()).isEqualTo(DEFAULT_MAX_PAGE_SIZE);
    }

    @Test
    void masksSensitiveAttributes_absentFlag_masks() throws JsonProcessingException {
        assertThat(enforcing(true, "{}").masksSensitiveAttributes()).isTrue();
        assertThat(enforcing(true, "{\"mask_sensitive_attributes\": false}").masksSensitiveAttributes())
                .isFalse();
    }

    @Test
    void maskedAttributeNames_returnsBareNamesOfTheScopeOnly() throws JsonProcessingException {
        ProductSearchContract contract = enforcing(
                true,
                """
                {"masked_filtered_attributes":
                    ["population_risk_tags", "organisation.authorised_classifications", "consumer.*"]}""");

        assertThat(contract.maskedAttributeNames(PolicyAttributeScopeCode.PRODUCT))
                .containsExactly("population_risk_tags");
        assertThat(contract.maskedAttributeNames(PolicyAttributeScopeCode.ORGANISATION))
                .containsExactly("authorised_classifications");
        assertThat(contract.maskedAttributeNames(PolicyAttributeScopeCode.CONSUMER))
                .containsExactly(ProductSearchContract.ALL_ATTRIBUTES);
        assertThat(contract.maskedAttributeNames(PolicyAttributeScopeCode.PRODUCER))
                .isEmpty();
    }

    @Test
    void textSearchFields_enforcing_isWhatIsListedSearchableAndVisible() throws JsonProcessingException {
        ProductSearchContract contract = enforcing(
                true,
                """
                {"text_search_fields": ["name", "description", "topic"],
                 "visible_fields": ["name", "description", "topic"],
                 "masked_filtered_fields": ["description"]}""");

        assertThat(contract.textSearchFields()).containsExactly(ProductField.NAME);
    }

    @Test
    void textSearchFields_enforcingWithNoneListed_isEmpty() throws JsonProcessingException {
        ProductSearchContract contract =
                enforcing(true, """
                {"visible_fields": ["name", "description"]}""");

        assertThat(contract.textSearchFields()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // The contract is shared: a view decision yields the same one
    // ---------------------------------------------------------------------------------------

    /**
     * Reading one product is a search constrained to that product, and this is what makes that
     * true rather than merely intended: the same contract terms, written by a {@code product.view}
     * rule and by a {@code product.discover} rule, yield the same contract. Neither endpoint can
     * acquire its own idea of what is withheld, because there is only one reader.
     */
    @Test
    void enforcing_viewDecision_yieldsTheSameContractAsAnEquivalentDiscoveryDecision() throws JsonProcessingException {
        String sharedTerms =
                """
                "row_filter": {"type": "comparison", "field": "organisation.key",
                               "operator": "eq", "values": ["ENV"]},
                "visible_fields": ["name", "topic", "organisation", "consumers"],
                "masked_filtered_fields": ["topic", "organisation.name"],
                "masked_filtered_attributes": ["population_risk_tags", "consumer.*"],
                "unmask_when": [{"names": ["consumers"],
                                 "when": {"type": "literal", "value": true}}],
                "mask_sensitive_attributes": true,
                "obligations": ["audit_access", "mask_response"]""";

        ProductSearchContract view = enforcingView(true, "{\"access_level\": \"summary\", " + sharedTerms + "}");
        ProductSearchContract discovery = enforcing(true, "{" + sharedTerms + "}");

        assertThat(view.isEnforced()).isEqualTo(discovery.isEnforced()).isTrue();
        assertThat(view.rowFilter()).isEqualTo(discovery.rowFilter());
        assertThat(view.maskedFields())
                .isEqualTo(discovery.maskedFields())
                .containsExactly("organisation.name", "topic");
        assertThat(view.maskedAttributes()).isEqualTo(discovery.maskedAttributes());
        assertThat(view.unmaskRules()).isEqualTo(discovery.unmaskRules()).hasSize(1);
        assertThat(view.masksSensitiveAttributes())
                .isEqualTo(discovery.masksSensitiveAttributes())
                .isTrue();
        assertThat(view.obligations()).isEqualTo(discovery.obligations());
        assertThat(view.maskedAttributeNames(PolicyAttributeScopeCode.CONSUMER))
                .isEqualTo(discovery.maskedAttributeNames(PolicyAttributeScopeCode.CONSUMER));
        // Visibility is the same judgement for both, block by block.
        assertThat(view.isVisible("name"))
                .isEqualTo(discovery.isVisible("name"))
                .isTrue();
        assertThat(view.isVisible("topic"))
                .isEqualTo(discovery.isVisible("topic"))
                .isFalse();
        assertThat(view.isVisible(ProductBlock.ORGANISATION.apiName()))
                .isEqualTo(discovery.isVisible(ProductBlock.ORGANISATION.apiName()))
                .isTrue();
        assertThat(view.isMasked("organisation.name"))
                .isEqualTo(discovery.isMasked("organisation.name"))
                .isTrue();
    }

    @Test
    void rowFilter_viewDecisionWithoutOne_deniesEveryProduct() throws JsonProcessingException {
        // The withholding default reaches the contract unchanged: no row filter means no product.
        assertThat(enforcingView(true, "{\"access_level\": \"full\"}").rowFilter())
                .isEqualTo(FilterNode.DENY_ALL);
    }

    @Test
    void rowFilter_deniedViewDecision_deniesEveryProduct() throws JsonProcessingException {
        assertThat(enforcingView(false, """
                {"row_filter": {"type": "literal", "value": true}}""")
                        .rowFilter())
                .isEqualTo(FilterNode.DENY_ALL);
    }

    /**
     * A view decision carries the <em>whole</em> contract, the search terms included, because the
     * view rule answers exactly what the discover rule answers - a view is that search constrained
     * to one product. So the contract reads them from a view decision just as it does from a
     * search's, and nothing here is conditional on which rule replied.
     *
     * <p>The view service never consults them: it supplies no caller criteria, only the id. That
     * they are present and correct anyway is what keeps the two endpoints from drifting.
     */
    @Test
    void enforcing_viewDecision_carriesTheSearchTermsTheSameWayASearchDoes() throws JsonProcessingException {
        ProductSearchContract contract = enforcingView(
                true,
                """
                {"access_level": "full",
                 "row_filter": {"type": "literal", "value": true},
                 "visible_fields": ["name", "description"],
                 "allowed_filtered_fields": ["name", "topic"],
                 "allowed_filtered_attributes": ["identifiability"],
                 "text_search_fields": ["name", "description"],
                 "max_page_size": 50}""");

        assertThat(contract.filterableFields()).containsExactly("name", "topic");
        assertThat(contract.filterableAttributes()).containsExactly("identifiability");
        assertThat(contract.mayFilterOn(FilterTarget.ofField("name"))).isTrue();
        assertThat(contract.mayFilterOn(FilterTarget.ofAttribute("identifiability")))
                .isTrue();
        assertThat(contract.maxPageSize()).isEqualTo(50);
    }

    /**
     * And a decision that declares none of them still reads in the withholding direction, whichever
     * rule replied: nothing filterable, and the configured page size rather than an unbounded one.
     */
    @Test
    void enforcing_viewDecisionWithoutSearchTerms_permitsNoCriteriaAndFallsBackToTheDefaultPageSize()
            throws JsonProcessingException {
        ProductSearchContract contract = enforcingView(true, "{\"access_level\": \"full\"}");

        assertThat(contract.filterableFields()).isEmpty();
        assertThat(contract.filterableAttributes()).isEmpty();
        assertThat(contract.textSearchFields()).isEmpty();
        assertThat(contract.mayFilterOn(FilterTarget.ofField("name"))).isFalse();
        assertThat(contract.maxPageSize()).isEqualTo(DEFAULT_MAX_PAGE_SIZE);
    }

    private ProductSearchContract enforcingView(boolean allow, String detailsJson) throws JsonProcessingException {
        ProductViewPolicyDecisionDetails details =
                objectMapper.readValue(detailsJson, ProductViewPolicyDecisionDetails.class);
        PolicyDecision<ProductViewPolicyDecisionDetails> decision =
                new PolicyDecision<>(allow, List.of(), PolicyProvenance.NONE, details);
        return ProductSearchContract.enforcing(decision);
    }

    private ProductSearchContract enforcing(boolean allow, String detailsJson) throws JsonProcessingException {
        ProductDiscoveryPolicyDecisionDetails details =
                objectMapper.readValue(detailsJson, ProductDiscoveryPolicyDecisionDetails.class);
        PolicyDecision<ProductDiscoveryPolicyDecisionDetails> decision =
                new PolicyDecision<>(allow, List.of(), PolicyProvenance.NONE, details);
        return ProductSearchContract.enforcing(decision);
    }
}
