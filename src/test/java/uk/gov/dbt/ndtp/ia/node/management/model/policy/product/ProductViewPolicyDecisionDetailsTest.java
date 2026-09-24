/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.policy.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.ComparisonOperator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterTarget;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionDetails;

/**
 * Covers how a {@code product.view} decision's details are read.
 *
 * <p>Most of what is asserted here is inherited from {@link ProductPolicyContractDetails}, and that
 * is the point: reading one product is a search constrained to that product, so the contract a view
 * decision carries is the same contract discovery is given and is read by the same code.
 *
 * <p>The tests that matter most are the ones about <em>absence</em>. Every missing field must read
 * in the withholding direction - an absent row filter matching nothing rather than everything, an
 * absent sensitivity flag masking, an absent list being empty rather than null. A rule that forgets
 * a field must therefore narrow access, never widen it, and these tests are what say so.
 */
class ProductViewPolicyDecisionDetailsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** The details in the shape {@code policies.product.view} returns them. */
    private static final String FULL_DETAILS =
            """
            {"access_level": "full",
             "required_clearance": "OFFICIAL-SENSITIVE",
             "row_filter": {"type": "comparison", "field": "organisation.key",
                            "operator": "eq", "values": ["ENV"]},
             "visible_fields": ["name", "topic", "description", "organisation", "producer"],
             "masked_filtered_fields": ["source", "organisation.name"],
             "masked_filtered_attributes": ["population_risk_tags", "consumer.*"],
             "unmask_when": [
               {"names": ["consumers", "subscribedBy"],
                "when": {"type": "comparison", "field": "organisation.key",
                         "operator": "eq", "values": ["ENV"]}}],
             "mask_sensitive_attributes": false,
             "obligations": ["audit_access", "mask_response"],
             "filter_contract": "management-node.filter/1"}""";

    private ProductViewPolicyDecisionDetails read(String json) throws JsonProcessingException {
        return objectMapper.readValue(json, ProductViewPolicyDecisionDetails.class);
    }

    // ---------------------------------------------------------------------------------------
    // What the rule said
    // ---------------------------------------------------------------------------------------

    @Test
    void accessLevel_ruleThatGrantedIt_isRead() throws JsonProcessingException {
        assertThat(read(FULL_DETAILS).accessLevel()).isEqualTo("full");
        assertThat(read("{\"access_level\": \"summary\"}").accessLevel()).isEqualTo("summary");
    }

    @Test
    void rowFilter_ruleThatSetOne_isTheRulesPredicate() throws JsonProcessingException {
        assertThat(read(FULL_DETAILS).rowFilter())
                .isEqualTo(new FilterNode.Comparison(
                        FilterTarget.parse(null, true, "organisation.key"), ComparisonOperator.EQ, List.of("ENV")));
    }

    @Test
    void contractFields_areReadFromTheirSnakeCaseNames() throws JsonProcessingException {
        ProductViewPolicyDecisionDetails details = read(FULL_DETAILS);

        assertThat(details.visibleFields()).containsExactly("name", "topic", "description", "organisation", "producer");
        assertThat(details.maskedFilteredFields()).containsExactly("source", "organisation.name");
        assertThat(details.maskedFilteredAttributes()).containsExactly("population_risk_tags", "consumer.*");
        assertThat(details.maskSensitiveAttributes()).isFalse();
        assertThat(details.obligations()).containsExactly("audit_access", "mask_response");
    }

    @Test
    void unmaskWhen_isReadWithItsNamesAndCondition() throws JsonProcessingException {
        assertThat(read(FULL_DETAILS).unmaskWhen())
                .containsExactly(new ProductPolicyContractDetails.UnmaskRule(
                        List.of("consumers", "subscribedBy"),
                        new FilterNode.Comparison(
                                FilterTarget.parse(null, true, "organisation.key"),
                                ComparisonOperator.EQ,
                                List.of("ENV"))));
    }

    // ---------------------------------------------------------------------------------------
    // What the rule did not say: everything missing withholds
    // ---------------------------------------------------------------------------------------

    /**
     * The single most important assertion in this class. A rule that returns no row filter must
     * match <em>no</em> product, because the alternative - reading "no filter" as "every product" -
     * would hand the whole catalogue to any caller who passed the request-level clearance gate, one
     * id at a time, and would look like a working endpoint while doing it.
     */
    @Test
    void rowFilter_ruleThatSetNone_matchesNothing() throws JsonProcessingException {
        assertThat(read("{}").rowFilter()).isEqualTo(FilterNode.DENY_ALL);
        assertThat(read("{\"access_level\": \"full\"}").rowFilter()).isEqualTo(FilterNode.DENY_ALL);
        assertThat(read("{\"row_filter\": null}").rowFilter()).isEqualTo(FilterNode.DENY_ALL);
    }

    @Test
    void maskSensitiveAttributes_ruleThatSetNone_masks() throws JsonProcessingException {
        assertThat(read("{}").maskSensitiveAttributes()).isTrue();
        assertThat(read("{\"mask_sensitive_attributes\": null}").maskSensitiveAttributes())
                .isTrue();
        assertThat(read("{\"mask_sensitive_attributes\": true}").maskSensitiveAttributes())
                .isTrue();
        assertThat(read("{\"mask_sensitive_attributes\": false}").maskSensitiveAttributes())
                .isFalse();
    }

    @Test
    void lists_ruleThatSetNone_areEmptyNeverNull() throws JsonProcessingException {
        ProductViewPolicyDecisionDetails details = read("{}");

        assertThat(details.visibleFields()).isNotNull().isEmpty();
        assertThat(details.maskedFilteredFields()).isNotNull().isEmpty();
        assertThat(details.maskedFilteredAttributes()).isNotNull().isEmpty();
        assertThat(details.unmaskWhen()).isNotNull().isEmpty();
        assertThat(details.obligations()).isNotNull().isEmpty();
        assertThat(details.accessLevel()).isNull();
    }

    @Test
    void lists_areNotModifiableByTheirReader() throws JsonProcessingException {
        ProductViewPolicyDecisionDetails details = read(FULL_DETAILS);

        assertThat(details.visibleFields()).isUnmodifiable();
        assertThat(details.maskedFilteredFields()).isUnmodifiable();
        assertThat(details.obligations()).isUnmodifiable();
    }

    @Test
    void unmaskRule_withoutACondition_showsNothing() {
        assertThat(new ProductPolicyContractDetails.UnmaskRule(null, null)).satisfies(rule -> {
            assertThat(rule.names()).isEmpty();
            assertThat(rule.when()).isEqualTo(FilterNode.DENY_ALL);
        });
    }

    // ---------------------------------------------------------------------------------------
    // Growing the rule without breaking the reader
    // ---------------------------------------------------------------------------------------

    @Test
    void unknownFields_areKeptRatherThanRefused() throws JsonProcessingException {
        ProductViewPolicyDecisionDetails details = read(FULL_DETAILS);

        assertThat(details.additional())
                .containsEntry("required_clearance", "OFFICIAL-SENSITIVE")
                .containsEntry("filter_contract", "management-node.filter/1")
                // Fields the class declares are its own, not "additional".
                .doesNotContainKeys("access_level", "row_filter", "visible_fields", "obligations");
    }

    @Test
    void read_detailsTheRuleNeverReturned_bindsWithoutFailing() throws JsonProcessingException {
        assertThat(read("{\"something_new\": {\"nested\": [1, 2]}}").additional())
                .containsKey("something_new");
    }

    // ---------------------------------------------------------------------------------------
    // Value semantics
    // ---------------------------------------------------------------------------------------

    @Test
    void equals_comparesTheFieldsIncludingTheInheritedContract() throws JsonProcessingException {
        assertThat(read(FULL_DETAILS)).isEqualTo(read(FULL_DETAILS)).hasSameHashCodeAs(read(FULL_DETAILS));

        assertThat(read("{\"access_level\": \"full\"}")).isNotEqualTo(read("{\"access_level\": \"summary\"}"));
        assertThat(read("{\"access_level\": \"full\"}"))
                .isNotEqualTo(
                        read("""
                        {"access_level": "full", "visible_fields": ["name"]}"""));
        assertThat(read("{\"access_level\": \"full\"}"))
                .isNotEqualTo(read(
                        """
                        {"access_level": "full", "mask_sensitive_attributes": false}"""));
        assertThat(read("{\"access_level\": \"full\"}"))
                .isNotEqualTo(
                        read("""
                        {"access_level": "full", "required_clearance": "SECRET"}"""));
    }

    @Test
    void empty_yieldsDetailsThatWithholdEverything() {
        ProductViewPolicyDecisionDetails empty = PolicyDecisionDetails.empty(ProductViewPolicyDecisionDetails.class);

        assertThat(empty).isEqualTo(new ProductViewPolicyDecisionDetails());
        assertThat(empty.accessLevel()).isNull();
        assertThat(empty.rowFilter()).isEqualTo(FilterNode.DENY_ALL);
        assertThat(empty.maskSensitiveAttributes()).isTrue();
        assertThat(empty.visibleFields()).isEmpty();
        assertThat(empty.additional()).isEmpty();
    }

    @Test
    void empty_equalsDetailsReadFromAnEmptyRuleDocument() throws JsonProcessingException {
        assertThat(PolicyDecisionDetails.empty(ProductViewPolicyDecisionDetails.class))
                .isEqualTo(read("{}"));
    }

    @Test
    void constructor_takingAnAccessLevel_setsOnlyThat() {
        ProductViewPolicyDecisionDetails details =
                new ProductViewPolicyDecisionDetails(ProductViewPolicyDecisionDetails.ACCESS_LEVEL_READ);

        assertThat(details.accessLevel()).isEqualTo("read");
        assertThat(details.rowFilter()).isEqualTo(FilterNode.DENY_ALL);
    }
}
