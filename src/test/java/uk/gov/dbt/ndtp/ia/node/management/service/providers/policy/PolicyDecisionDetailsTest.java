/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.Combinator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.ComparisonOperator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterTarget;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails.Filtering;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductPolicyContractDetails;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductSubscriptionPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductViewPolicyDecisionDetails;

/**
 * Covers the rule-specific details: the generic form keeps whatever the rule returned, and each
 * endpoint's subclass binds the {@code details} its Rego rule returns, keeping anything it does not
 * declare.
 */
class PolicyDecisionDetailsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    static class NoDefaultConstructor extends PolicyDecisionDetails {
        NoDefaultConstructor(String required) {}
    }

    private ProductDiscoveryPolicyDecisionDetails discoverDetails(String json) throws Exception {
        return objectMapper.readValue(json, ProductDiscoveryPolicyDecisionDetails.class);
    }

    @Test
    void generic_keepsEveryFieldInOrderAndIsUnmodifiable() throws Exception {
        PolicyDecisionDetails details =
                objectMapper.readValue("{\"zeta\": 1, \"access_level\": \"read\"}", PolicyDecisionDetails.class);

        assertThat(details.additional().keySet()).containsExactly("zeta", "access_level");
        assertThat(details.additional()).isUnmodifiable();
        assertThat(objectMapper.writeValueAsString(details)).isEqualTo("{\"zeta\":1,\"access_level\":\"read\"}");
    }

    @Test
    void discover_bindsTheDiscoverRulesDetails() throws Exception {
        ProductDiscoveryPolicyDecisionDetails details = objectMapper.readValue(
                """
                {"evaluation": "candidate",
                 "allowed_filtered_fields": ["name", "topic"], "denied_filtered_fields": ["source"],
                 "masked_filtered_fields": ["consumers"],
                 "allowed_filtered_attributes": ["identifiability"],
                 "denied_filtered_attributes": ["temporal_resolution"],
                 "masked_filtered_attributes": ["population_risk_tags"],
                 "max_page_size": 50,
                 "row_filter_hint": "kept for the reader"}""",
                ProductDiscoveryPolicyDecisionDetails.class);

        assertThat(details.evaluation()).isEqualTo(ProductDiscoveryPolicyDecisionDetails.EVALUATION_CANDIDATE);
        assertThat(details.fields())
                .isEqualTo(new Filtering(List.of("name", "topic"), List.of("source"), List.of("consumers")));
        assertThat(details.attributes())
                .isEqualTo(new Filtering(
                        List.of("identifiability"), List.of("temporal_resolution"), List.of("population_risk_tags")));
        assertThat(details.maxPageSize()).isEqualTo(50);
        // Anything the class does not declare still lands in additional().
        assertThat(details.additional()).isEqualTo(Map.of("row_filter_hint", "kept for the reader"));
    }

    @Test
    void discover_bindsTheSearchContractFromItsSnakeCaseNames() throws Exception {
        ProductDiscoveryPolicyDecisionDetails details = discoverDetails(
                """
                {"evaluation": "request",
                 "row_filter": {"type": "group", "combinator": "and", "nodes": [
                   {"type": "comparison", "attribute": "identifiability", "operator": "in",
                    "values": ["anonymised", "pseudonymised"]},
                   {"type": "comparison", "field": "organisation.key", "operator": "eq", "values": ["ENV"]}]},
                 "visible_fields": ["name", "topic", "producer"],
                 "text_search_fields": ["name", "description"],
                 "unmask_when": [{"names": ["consumers", "subscribed_by"],
                                  "when": {"type": "comparison", "field": "organisation.key",
                                           "operator": "eq", "values": ["ENV"]}}],
                 "mask_sensitive_attributes": false,
                 "max_page_size": 50,
                 "obligations": ["audit_access"]}""");

        FilterNode.Comparison ownOrganisation = new FilterNode.Comparison(
                new FilterTarget(FilterScope.ORGANISATION, true, "key"), ComparisonOperator.EQ, List.of("ENV"));
        assertThat(details.rowFilter())
                .isEqualTo(FilterNode.Group.and(List.of(
                        FilterNode.Comparison.ofAttribute(
                                "identifiability", ComparisonOperator.IN, "anonymised", "pseudonymised"),
                        ownOrganisation)));
        assertThat(details.visibleFields()).containsExactly("name", "topic", "producer");
        assertThat(details.textSearchFields()).containsExactly("name", "description");
        assertThat(details.unmaskWhen())
                .containsExactly(new ProductPolicyContractDetails.UnmaskRule(
                        List.of("consumers", "subscribed_by"), ownOrganisation));
        assertThat(details.maskSensitiveAttributes()).isFalse();
        assertThat(details.maxPageSize()).isEqualTo(50);
        assertThat(details.obligations()).containsExactly("audit_access");
        assertThat(details.additional()).isEmpty();
    }

    @Test
    void discover_absentSearchContract_readsInTheWithholdingDirection() throws Exception {
        ProductDiscoveryPolicyDecisionDetails details = discoverDetails("{\"evaluation\": \"request\"}");

        // No row filter is "matches nothing", never "no filter at all".
        assertThat(details.rowFilter()).isEqualTo(FilterNode.DENY_ALL);
        // An unstated sensitivity flag masks.
        assertThat(details.maskSensitiveAttributes()).isTrue();
        assertThat(details.visibleFields()).isEmpty();
        assertThat(details.textSearchFields()).isEmpty();
        assertThat(details.unmaskWhen()).isEmpty();
        assertThat(details.obligations()).isEmpty();
        assertThat(details.maxPageSize()).isNull();
    }

    @Test
    void discover_narrowedBy_combinesTheSearchContract() throws Exception {
        ProductDiscoveryPolicyDecisionDetails request = discoverDetails(
                """
                {"evaluation": "request",
                 "row_filter": {"type": "comparison", "field": "type", "operator": "eq", "values": ["topic"]},
                 "visible_fields": ["name", "topic", "source"],
                 "text_search_fields": ["name", "description"],
                 "unmask_when": [{"names": ["consumers"], "when": {"type": "literal", "value": true}}],
                 "mask_sensitive_attributes": false,
                 "max_page_size": 100,
                 "obligations": ["audit_access"]}""");
        ProductDiscoveryPolicyDecisionDetails candidate = discoverDetails(
                """
                {"evaluation": "candidate",
                 "row_filter": {"type": "comparison", "attribute": "identifiability", "operator": "in",
                                "values": ["anonymised"]},
                 "visible_fields": ["name", "source", "producer"],
                 "text_search_fields": ["name"],
                 "unmask_when": [{"names": ["consumers"], "when": {"type": "literal", "value": true}}],
                 "mask_sensitive_attributes": true,
                 "max_page_size": 25,
                 "obligations": ["log_query"]}""");

        ProductDiscoveryPolicyDecisionDetails combined = request.narrowedBy(candidate);

        // Both row filters must hold, so neither decision can widen what the other restricts.
        assertThat(combined.rowFilter())
                .isEqualTo(FilterNode.Group.and(List.of(request.rowFilter(), candidate.rowFilter())));
        assertThat(combined.visibleFields()).containsExactly("name", "source");
        assertThat(combined.textSearchFields()).containsExactly("name");
        // Identical in both, so the rule survives the combination.
        assertThat(combined.unmaskWhen()).isEqualTo(request.unmaskWhen());
        assertThat(combined.maskSensitiveAttributes()).isTrue();
        assertThat(combined.maxPageSize()).isEqualTo(25);
        assertThat(combined.obligations()).containsExactly("audit_access", "log_query");
    }

    @Test
    void discover_narrowedBy_dropsUnmaskRulesThatDiffer_andMasksOnlyWhenNeitherSaysOtherwise() throws Exception {
        ProductDiscoveryPolicyDecisionDetails request = discoverDetails(
                """
                {"evaluation": "request",
                 "unmask_when": [{"names": ["consumers"], "when": {"type": "literal", "value": true}}],
                 "mask_sensitive_attributes": false,
                 "max_page_size": 20}""");
        ProductDiscoveryPolicyDecisionDetails candidate = discoverDetails(
                """
                {"evaluation": "candidate",
                 "unmask_when": [{"names": ["subscribed_by"], "when": {"type": "literal", "value": true}}],
                 "mask_sensitive_attributes": false}""");

        ProductDiscoveryPolicyDecisionDetails combined = request.narrowedBy(candidate);

        // The two decisions disagree on what may be unmasked, so nothing is.
        assertThat(combined.unmaskWhen()).isEmpty();
        assertThat(combined.maskSensitiveAttributes()).isFalse();
        // Only one side stated a page size, so that is the limit.
        assertThat(combined.maxPageSize()).isEqualTo(20);
        // Neither stated a row filter, so the combination of two deny-alls still matches nothing.
        assertThat(combined.rowFilter())
                .isEqualTo(new FilterNode.Group(Combinator.AND, List.of(FilterNode.DENY_ALL, FilterNode.DENY_ALL)));
    }

    @Test
    void view_bindsTheProductFallbacksDetails() throws Exception {
        ProductViewPolicyDecisionDetails details =
                objectMapper.readValue("{\"access_level\": \"read\"}", ProductViewPolicyDecisionDetails.class);

        assertThat(details).isEqualTo(new ProductViewPolicyDecisionDetails("read"));
        assertThat(details.additional()).isEmpty();
    }

    @Test
    void subscribe_convertsFromTheGenericForm() {
        PolicyDecisionDetails generic = new PolicyDecisionDetails(Map.of(
                "requires_approval", false, "max_validity_days", 90, "permitted_schedule_types", List.of("cron")));

        assertThat(objectMapper.convertValue(generic, ProductSubscriptionPolicyDecisionDetails.class))
                .isEqualTo(new ProductSubscriptionPolicyDecisionDetails(false, 90, List.of("cron")));
    }

    @Test
    void missingFields_readAsNullOrEmptyLists() {
        ProductDiscoveryPolicyDecisionDetails details = new ProductDiscoveryPolicyDecisionDetails();

        assertThat(details.evaluation()).isNull();
        assertThat(details.allowedFilteredFields()).isEmpty();
        assertThat(details.deniedFilteredFields()).isEmpty();
        assertThat(details.maskedFilteredFields()).isEmpty();
        assertThat(details.allowedFilteredAttributes()).isEmpty();
        assertThat(details.deniedFilteredAttributes()).isEmpty();
        assertThat(details.maskedFilteredAttributes()).isEmpty();
    }

    @Test
    void generic_narrowedBy_takesTheNarrowerDetailsWhenTheyCarryAnything() {
        PolicyDecisionDetails request = new PolicyDecisionDetails(Map.of("access_level", "read"));
        PolicyDecisionDetails candidate = new PolicyDecisionDetails(Map.of("requires_approval", true));

        assertThat(request.narrowedBy(candidate)).isSameAs(candidate);
        assertThat(request.narrowedBy(new PolicyDecisionDetails())).isSameAs(request);
        assertThat(request.narrowedBy(null)).isSameAs(request);
    }

    @Test
    void filtering_narrowedBy_letsWithholdingWinOverDisclosure() {
        Filtering request = new Filtering(List.of("name", "topic"), List.of("internal_owner"), List.of());
        Filtering candidate = new Filtering(List.of("name", "source"), List.of(), List.of("topic"));

        // "topic" is allowed by the request but masked by the candidate, so it is not left in the
        // allowed list; the same holds for anything either denies.
        assertThat(request.narrowedBy(candidate))
                .isEqualTo(new Filtering(List.of("name", "source"), List.of("internal_owner"), List.of("topic")));
    }

    @Test
    void discover_narrowedBy_mergesFieldsAndAttributesSeparately() {
        ProductDiscoveryPolicyDecisionDetails request = new ProductDiscoveryPolicyDecisionDetails(
                ProductDiscoveryPolicyDecisionDetails.EVALUATION_REQUEST,
                new Filtering(List.of("name", "source"), List.of(), List.of("consumers")),
                new Filtering(List.of("identifiability"), List.of(), List.of()));
        ProductDiscoveryPolicyDecisionDetails candidate = new ProductDiscoveryPolicyDecisionDetails(
                ProductDiscoveryPolicyDecisionDetails.EVALUATION_CANDIDATE,
                new Filtering(List.of("name"), List.of(), List.of("source")),
                new Filtering(List.of("identifiability"), List.of(), List.of("population_risk_tags")));

        ProductDiscoveryPolicyDecisionDetails combined = request.narrowedBy(candidate);

        assertThat(combined.evaluation()).isEqualTo(ProductDiscoveryPolicyDecisionDetails.EVALUATION_CANDIDATE);
        assertThat(combined.fields())
                .isEqualTo(new Filtering(List.of("name"), List.of(), List.of("consumers", "source")));
        assertThat(combined.attributes())
                .isEqualTo(new Filtering(List.of("identifiability"), List.of(), List.of("population_risk_tags")));
    }

    @Test
    void discover_narrowedBy_emptyDetails_keepsThese() {
        ProductDiscoveryPolicyDecisionDetails request = new ProductDiscoveryPolicyDecisionDetails(
                ProductDiscoveryPolicyDecisionDetails.EVALUATION_REQUEST,
                new Filtering(List.of(), List.of(), List.of("consumers")),
                new Filtering(List.of(), List.of(), List.of()));

        assertThat(request.narrowedBy(new ProductDiscoveryPolicyDecisionDetails()))
                .isSameAs(request);
        assertThat(request.narrowedBy(null)).isSameAs(request);
    }

    @Test
    void discover_combinedDecision_keepsTheRequestMaskingWhenTheCandidateHasItsOwnDetails() {
        PolicyDecision<ProductDiscoveryPolicyDecisionDetails> request = PolicyDecision.of(
                        true, ProductDiscoveryPolicyDecisionDetails.class)
                .withDetails(new ProductDiscoveryPolicyDecisionDetails(
                        ProductDiscoveryPolicyDecisionDetails.EVALUATION_REQUEST,
                        new Filtering(List.of(), List.of(), List.of("consumers")),
                        new Filtering(List.of(), List.of(), List.of("population_risk_tags"))));
        PolicyDecision<ProductDiscoveryPolicyDecisionDetails> candidate = PolicyDecision.of(
                        true, ProductDiscoveryPolicyDecisionDetails.class)
                .withDetails(new ProductDiscoveryPolicyDecisionDetails(
                        ProductDiscoveryPolicyDecisionDetails.EVALUATION_CANDIDATE,
                        new Filtering(List.of("name"), List.of(), List.of()),
                        new Filtering(List.of("identifiability"), List.of(), List.of())));

        ProductDiscoveryPolicyDecisionDetails details =
                request.combinedWith(candidate).details();

        assertThat(details.evaluation()).isEqualTo(ProductDiscoveryPolicyDecisionDetails.EVALUATION_CANDIDATE);
        assertThat(details.maskedFilteredFields()).containsExactly("consumers");
        assertThat(details.maskedFilteredAttributes()).containsExactly("population_risk_tags");
        assertThat(details.allowedFilteredAttributes()).containsExactly("identifiability");
    }

    @Test
    void empty_isANewInstanceWithNothingSet() {
        assertThat(PolicyDecisionDetails.empty(ProductViewPolicyDecisionDetails.class))
                .isEqualTo(new ProductViewPolicyDecisionDetails());
        assertThat(PolicyDecisionDetails.empty(PolicyDecisionDetails.class).additional())
                .isEmpty();
    }

    @Test
    void empty_withoutANoArgumentConstructor_throws() {
        assertThatThrownBy(() -> PolicyDecisionDetails.empty(NoDefaultConstructor.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(NoDefaultConstructor.class.getName());
    }
}
