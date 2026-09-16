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
                {"evaluation": "candidate", "permitted_nationalities": ["GB"],
                 "excluded_classifications": ["SECRET"], "added_later": true}""",
                ProductDiscoveryPolicyDecisionDetails.class);

        assertThat(details.evaluation()).isEqualTo(ProductDiscoveryPolicyDecisionDetails.EVALUATION_CANDIDATE);
        assertThat(details.permittedNationalities()).containsExactly("GB");
        assertThat(details.excludedClassifications()).containsExactly("SECRET");
        assertThat(details.additional()).isEqualTo(Map.of("added_later", true));
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
        assertThat(details.permittedNationalities()).isEmpty();
        assertThat(details.excludedClassifications()).isEmpty();
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
