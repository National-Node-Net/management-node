/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.EqualsAndHashCode;
import org.junit.jupiter.api.Test;

/**
 * Covers the decision document every policy returns: how each shape of PDP result is read into
 * it, that its parts are never null, and how a whole-request decision and a
 * per-candidate one combine.
 */
class PolicyDecisionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PolicyDecision<PolicyDecisionDetails> parseResult(String json) throws Exception {
        return objectMapper.readValue(json, PolicyDecisionResponse.class).result();
    }

    @Test
    void objectResult_readsTheVerdict() throws Exception {
        PolicyDecision<PolicyDecisionDetails> output = parseResult("{\"result\": {\"allow\": true}}");

        assertThat(output.allow()).isTrue();
        assertThat(output.verdict()).isEqualTo(PolicyVerdict.ALLOW);
        assertThat(output).isEqualTo(PolicyDecision.ALLOW);
    }

    /** Attribute filtering is a rule's own term, so a top-level list is not part of the envelope. */
    @Test
    void objectResult_withTopLevelAttributeLists_ignoresThem() throws Exception {
        PolicyDecision<PolicyDecisionDetails> output = parseResult(
                """
                {"result": {"allow": true,
                            "allowed_filtered_attributes": ["name", "topic"],
                            "masked_filtered_attributes": ["contact_email"]}}""");

        assertThat(output).isEqualTo(PolicyDecision.ALLOW);
        assertThat(output.details().additional()).isEmpty();
    }

    @Test
    void objectResult_withoutAllow_isDeny() throws Exception {
        assertThat(parseResult("{\"result\": {\"reasons\": [\"a\"]}}").allow()).isFalse();
    }

    @Test
    void objectResult_withUnknownFields_isStillRead() throws Exception {
        assertThat(parseResult("{\"result\": {\"allow\": true, \"reason\": \"nationality\"}}"))
                .isEqualTo(PolicyDecision.ALLOW);
    }

    /** A policy still answering with OPA's plain boolean result keeps working. */
    @Test
    void booleanResult_isReadAsTheVerdictWithNothingElse() throws Exception {
        assertThat(parseResult("{\"result\": true}")).isEqualTo(PolicyDecision.ALLOW);
        assertThat(parseResult("{\"result\": false}")).isEqualTo(PolicyDecision.DENY);
    }

    /** A result that cannot be understood must not be read as permission. */
    @Test
    void unreadableResult_isDeny() throws Exception {
        assertThat(parseResult("{\"result\": \"true\"}")).isEqualTo(PolicyDecision.DENY);
        assertThat(parseResult("{\"result\": 1}")).isEqualTo(PolicyDecision.DENY);
        assertThat(parseResult("{\"result\": [\"allow\"]}")).isEqualTo(PolicyDecision.DENY);
        assertThat(parseResult("{\"result\": null}")).isNull();
    }

    @Test
    void serialisedShape_usesTheSnakeCaseNamesThePolicyReturns() throws Exception {
        String json = objectMapper.writeValueAsString(PolicyDecision.ALLOW);

        assertThat(json)
                .isEqualTo("{\"allow\":true,"
                        + "\"reasons\":[],"
                        + "\"policy\":{\"id\":\"none\",\"version\":\"none\",\"resolution\":\"none\"},"
                        + "\"details\":{}}");
    }

    @Test
    void combinedWith_permitsOnlyWhenBothPermit() {
        assertThat(PolicyDecision.ALLOW.combinedWith(PolicyDecision.ALLOW).allow())
                .isTrue();
        assertThat(PolicyDecision.ALLOW.combinedWith(PolicyDecision.DENY).allow())
                .isFalse();
        assertThat(PolicyDecision.DENY.combinedWith(PolicyDecision.ALLOW).allow())
                .isFalse();
    }

    @Test
    void callerReasons_leaveOutReasonsAboutPolicyWiring() {
        PolicyDecision<PolicyDecisionDetails> decision = new PolicyDecision<>(
                false,
                List.of(
                        "dispatch.resource_fallback",
                        "organisation.clearance_insufficient",
                        "policy.details_unreadable",
                        "schedule.type_not_permitted"),
                FALLBACK,
                new PolicyDecisionDetails());

        assertThat(decision.callerReasons())
                .containsExactly("organisation.clearance_insufficient", "schedule.type_not_permitted");
        assertThat(PolicyDecision.DENY.callerReasons()).isEmpty();
    }

    @Test
    void combinedWith_null_leavesTheDecisionUnchanged() {
        PolicyDecision<PolicyDecisionDetails> decision =
                new PolicyDecision<>(true, List.of("r"), SUBSCRIBE, new PolicyDecisionDetails(Map.of("a", 1)));

        assertThat(decision.combinedWith(null)).isEqualTo(decision);
    }

    @Test
    void reasons_areImmutable() {
        PolicyDecision<PolicyDecisionDetails> output =
                new PolicyDecision<>(true, Arrays.asList("r"), null, new PolicyDecisionDetails());

        assertThat(output.reasons()).isUnmodifiable();
    }

    private static final PolicyProvenance FALLBACK =
            new PolicyProvenance("product.fallback", "policies.product.fallback/1.0.0", "resource_fallback");

    private static final PolicyProvenance SUBSCRIBE =
            new PolicyProvenance("product.subscribe", "policies.product.subscribe/1.0.0", "exact");

    @Test
    void objectResult_readsReasonsProvenanceAndDetails() throws Exception {
        PolicyDecision<PolicyDecisionDetails> output = parseResult(
                """
                {"result": {"allow": true,
                            "reasons": ["dispatch.resource_fallback"],
                            "policy": {"id": "product.fallback",
                                       "version": "policies.product.fallback/1.0.0",
                                       "resolution": "resource_fallback"},
                            "details": {"zeta": 1, "access_level": "read", "nested": {"a": [1, 2]}}}}""");

        assertThat(output.reasons()).containsExactly("dispatch.resource_fallback");
        assertThat(output.policy()).isEqualTo(FALLBACK);
        Map<String, Object> details = output.details().additional();
        // Insertion order is kept, so a logged or re-serialised document reads as the rule wrote it.
        assertThat(details.keySet()).containsExactly("zeta", "access_level", "nested");
        assertThat(details.get("nested")).isEqualTo(Map.of("a", List.of(1, 2)));
    }

    @Test
    void objectResult_withoutReasonsPolicyOrDetails_isNormalisedRatherThanNull() throws Exception {
        PolicyDecision<PolicyDecisionDetails> output =
                parseResult("{\"result\": {\"allow\": true, \"details\": null}}");

        assertThat(output.reasons()).isEmpty();
        assertThat(output.policy()).isEqualTo(PolicyProvenance.NONE);
        assertThat(output.details()).isEqualTo(new PolicyDecisionDetails());
    }

    /** Details that are not an object break the shared contract, so they cannot grant anything. */
    @Test
    void objectResult_withNonObjectDetails_isDenyKeepingProvenance() throws Exception {
        PolicyDecision<PolicyDecisionDetails> output = parseResult(
                """
                {"result": {"allow": true, "details": ["read"],
                            "policy": {"id": "product.fallback",
                                       "version": "policies.product.fallback/1.0.0",
                                       "resolution": "resource_fallback"}}}""");

        assertThat(output.allow()).isFalse();
        assertThat(output.reasons()).containsExactly(PolicyDecision.REASON_DETAILS_UNREADABLE);
        assertThat(output.policy()).isEqualTo(FALLBACK);
    }

    @Test
    void nullComponents_areNormalised() {
        PolicyDecision<PolicyDecisionDetails> output =
                new PolicyDecision<>(true, null, null, new PolicyDecisionDetails());

        assertThat(output).isEqualTo(PolicyDecision.ALLOW);
        assertThat(output.reasons()).isUnmodifiable();
    }

    @Test
    void nullDetails_areRefused() {
        assertThatThrownBy(() -> new PolicyDecision<>(true, null, null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void deny_carriesTheReasonAndNothingElse() {
        PolicyDecision<PolicyDecisionDetails> output = PolicyDecision.deny("organisation.missing");

        assertThat(output.allow()).isFalse();
        assertThat(output.reasons()).containsExactly("organisation.missing");
        assertThat(output.policy()).isEqualTo(PolicyProvenance.NONE);
        assertThat(output.details()).isEqualTo(new PolicyDecisionDetails());
    }

    @Test
    void deny_withProvenanceAndType_carriesEmptyDetailsOfThatType() {
        PolicyDecision<Grant> output = PolicyDecision.deny("policy.details_unreadable", SUBSCRIBE, Grant.class);

        assertThat(output.allow()).isFalse();
        assertThat(output.policy()).isEqualTo(SUBSCRIBE);
        assertThat(output.details()).isEqualTo(new Grant());
    }

    @Test
    void ofType_carriesEmptyDetailsOfThatType() {
        PolicyDecision<Grant> output = PolicyDecision.of(true, Grant.class);

        assertThat(output.allow()).isTrue();
        assertThat(output.details()).isEqualTo(new Grant());
    }

    @Test
    void withDetails_replacesOnlyTheDetails() {
        PolicyDecision<PolicyDecisionDetails> output =
                new PolicyDecision<>(true, List.of("r"), SUBSCRIBE, new PolicyDecisionDetails(Map.of("a", 1)));

        PolicyDecision<Grant> typed = output.withDetails(new Grant(false, 7));

        assertThat(typed).isEqualTo(new PolicyDecision<>(true, List.of("r"), SUBSCRIBE, new Grant(false, 7)));
    }

    @Test
    void combinedWith_mergesReasonsSortedAndDeduplicated() {
        PolicyDecision<PolicyDecisionDetails> request =
                new PolicyDecision<>(true, List.of("z.last", "a.first"), FALLBACK, new PolicyDecisionDetails());
        PolicyDecision<PolicyDecisionDetails> candidate =
                new PolicyDecision<>(false, List.of("m.middle", "a.first"), SUBSCRIBE, new PolicyDecisionDetails());

        assertThat(request.combinedWith(candidate).reasons()).containsExactly("a.first", "m.middle", "z.last");
    }

    @Test
    void combinedWith_prefersTheNarrowerDecisionsProvenanceAndDetailsWhenPresent() {
        PolicyDecision<PolicyDecisionDetails> request = new PolicyDecision<>(
                true, List.of(), FALLBACK, new PolicyDecisionDetails(Map.of("access_level", "read")));
        PolicyDecision<PolicyDecisionDetails> candidate = new PolicyDecision<>(
                true, List.of(), SUBSCRIBE, new PolicyDecisionDetails(Map.of("requires_approval", true)));

        PolicyDecision<PolicyDecisionDetails> combined = request.combinedWith(candidate);

        assertThat(combined.policy()).isEqualTo(SUBSCRIBE);
        assertThat(combined.details().additional()).isEqualTo(Map.of("requires_approval", true));
    }

    @Test
    void combinedWith_keepsThisDecisionsProvenanceAndDetailsWhenTheOtherHasNone() {
        PolicyDecision<PolicyDecisionDetails> request = new PolicyDecision<>(
                true, List.of(), FALLBACK, new PolicyDecisionDetails(Map.of("access_level", "read")));

        PolicyDecision<PolicyDecisionDetails> combined = request.combinedWith(PolicyDecision.ALLOW);

        assertThat(combined.policy()).isEqualTo(FALLBACK);
        assertThat(combined.details().additional()).isEqualTo(Map.of("access_level", "read"));
    }

    @Test
    void combinedWith_typedDetails_keepThisDecisionsWhenTheOthersAreEmpty() {
        PolicyDecision<Grant> request = PolicyDecision.of(true, Grant.class).withDetails(new Grant(true, 30));

        PolicyDecision<Grant> combined = request.combinedWith(PolicyDecision.of(true, Grant.class));

        assertThat(combined.details()).isEqualTo(new Grant(true, 30));
    }

    /** A details subclass, as an endpoint would declare for its rule. */
    @EqualsAndHashCode(callSuper = true)
    static class Grant extends PolicyDecisionDetails {
        private Boolean requiresApproval;
        private Integer maxValidityDays;

        Grant() {}

        Grant(boolean requiresApproval, int maxValidityDays) {
            this.requiresApproval = requiresApproval;
            this.maxValidityDays = maxValidityDays;
        }
    }
}
