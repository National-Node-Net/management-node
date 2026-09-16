/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Covers the decision document every policy returns: how each shape of PDP result is read into
 * it, that its attribute lists are never null, and how a whole-request decision and a
 * per-candidate one combine.
 */
class DefaultPolicyDecisionOutputTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DefaultPolicyDecisionOutput parseResult(String json) throws Exception {
        return objectMapper.readValue(json, PolicyDecisionResponse.class).result();
    }

    @Test
    void objectResult_readsVerdictAndEveryAttributeList() throws Exception {
        DefaultPolicyDecisionOutput output = parseResult(
                """
                {"result": {"allow": true,
                            "allowed_filtered_attributes": ["name", "topic"],
                            "denied_filtered_attributes": ["internal_owner"],
                            "masked_filtered_attributes": ["contact_email"]}}""");

        assertThat(output.allow()).isTrue();
        assertThat(output.decision()).isEqualTo(PolicyDecision.ALLOW);
        assertThat(output.allowedFilteredAttributes()).containsExactly("name", "topic");
        assertThat(output.deniedFilteredAttributes()).containsExactly("internal_owner");
        assertThat(output.maskedFilteredAttributes()).containsExactly("contact_email");
    }

    @Test
    void objectResult_withoutLists_yieldsEmptyListsRatherThanNulls() throws Exception {
        DefaultPolicyDecisionOutput output = parseResult("{\"result\": {\"allow\": true}}");

        assertThat(output).isEqualTo(DefaultPolicyDecisionOutput.ALLOW);
        assertThat(output.allowedFilteredAttributes()).isEmpty();
        assertThat(output.deniedFilteredAttributes()).isEmpty();
        assertThat(output.maskedFilteredAttributes()).isEmpty();
    }

    @Test
    void objectResult_withoutAllow_isDeny() throws Exception {
        assertThat(parseResult("{\"result\": {\"denied_filtered_attributes\": [\"a\"]}}")
                        .allow())
                .isFalse();
    }

    @Test
    void objectResult_withUnknownFields_isStillRead() throws Exception {
        assertThat(parseResult("{\"result\": {\"allow\": true, \"reason\": \"nationality\"}}"))
                .isEqualTo(DefaultPolicyDecisionOutput.ALLOW);
    }

    /** A policy still answering with OPA's plain boolean result keeps working. */
    @Test
    void booleanResult_isReadAsTheVerdictWithNothingFiltered() throws Exception {
        assertThat(parseResult("{\"result\": true}")).isEqualTo(DefaultPolicyDecisionOutput.ALLOW);
        assertThat(parseResult("{\"result\": false}")).isEqualTo(DefaultPolicyDecisionOutput.DENY);
    }

    /** A result that cannot be understood must not be read as permission. */
    @Test
    void unreadableResult_isDeny() throws Exception {
        assertThat(parseResult("{\"result\": \"true\"}")).isEqualTo(DefaultPolicyDecisionOutput.DENY);
        assertThat(parseResult("{\"result\": 1}")).isEqualTo(DefaultPolicyDecisionOutput.DENY);
        assertThat(parseResult("{\"result\": [\"allow\"]}")).isEqualTo(DefaultPolicyDecisionOutput.DENY);
        assertThat(parseResult("{\"result\": null}")).isNull();
    }

    @Test
    void nullLists_areNormalisedToEmpty() {
        DefaultPolicyDecisionOutput output = new DefaultPolicyDecisionOutput(true, null, null, null);

        assertThat(output).isEqualTo(DefaultPolicyDecisionOutput.ALLOW);
    }

    @Test
    void serialisedShape_usesTheSnakeCaseNamesThePolicyReturns() throws Exception {
        String json = objectMapper.writeValueAsString(new DefaultPolicyDecisionOutput(
                true, List.of("name"), List.of("internal_owner"), List.of("contact_email")));

        assertThat(json)
                .isEqualTo("{\"allow\":true,\"allowed_filtered_attributes\":[\"name\"],"
                        + "\"denied_filtered_attributes\":[\"internal_owner\"],"
                        + "\"masked_filtered_attributes\":[\"contact_email\"]}");
    }

    @Test
    void combinedWith_permitsOnlyWhenBothPermit() {
        assertThat(DefaultPolicyDecisionOutput.ALLOW
                        .combinedWith(DefaultPolicyDecisionOutput.ALLOW)
                        .allow())
                .isTrue();
        assertThat(DefaultPolicyDecisionOutput.ALLOW
                        .combinedWith(DefaultPolicyDecisionOutput.DENY)
                        .allow())
                .isFalse();
        assertThat(DefaultPolicyDecisionOutput.DENY
                        .combinedWith(DefaultPolicyDecisionOutput.ALLOW)
                        .allow())
                .isFalse();
    }

    @Test
    void combinedWith_mergesListsAndLetsWithholdingWinOverDisclosure() {
        DefaultPolicyDecisionOutput request =
                new DefaultPolicyDecisionOutput(true, List.of("name", "topic"), List.of("internal_owner"), List.of());
        DefaultPolicyDecisionOutput candidate =
                new DefaultPolicyDecisionOutput(true, List.of("name", "source"), List.of(), List.of("topic"));

        DefaultPolicyDecisionOutput combined = request.combinedWith(candidate);

        // "topic" is allowed by the request decision but masked by the candidate's, so it is not
        // left in the allowed list; the same holds for anything either decision denies.
        assertThat(combined.allowedFilteredAttributes()).containsExactly("name", "source");
        assertThat(combined.deniedFilteredAttributes()).containsExactly("internal_owner");
        assertThat(combined.maskedFilteredAttributes()).containsExactly("topic");
    }

    @Test
    void combinedWith_null_leavesTheDecisionUnchanged() {
        DefaultPolicyDecisionOutput decision =
                new DefaultPolicyDecisionOutput(true, List.of("name"), List.of(), List.of());

        assertThat(decision.combinedWith(null)).isEqualTo(decision);
    }

    @Test
    void attributeLists_areImmutable() {
        DefaultPolicyDecisionOutput output =
                new DefaultPolicyDecisionOutput(true, Arrays.asList("name"), List.of(), List.of());

        assertThat(output.allowedFilteredAttributes()).isUnmodifiable();
    }
}
