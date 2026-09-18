/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails.Filtering;
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
                 "max_page_size": 50}""",
                ProductDiscoveryPolicyDecisionDetails.class);

        assertThat(details.evaluation()).isEqualTo(ProductDiscoveryPolicyDecisionDetails.EVALUATION_CANDIDATE);
        assertThat(details.fields())
                .isEqualTo(new Filtering(List.of("name", "topic"), List.of("source"), List.of("consumers")));
        assertThat(details.attributes())
                .isEqualTo(new Filtering(
                        List.of("identifiability"), List.of("temporal_resolution"), List.of("population_risk_tags")));
        assertThat(details.additional()).isEqualTo(Map.of("max_page_size", 50));
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
