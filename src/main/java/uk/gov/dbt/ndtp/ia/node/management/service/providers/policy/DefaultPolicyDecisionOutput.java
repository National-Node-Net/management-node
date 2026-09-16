/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The decision document every policy returns, whatever it is deciding about: one verdict plus
 * three attribute lists describing what the subject may see of the resource.
 *
 * <pre>
 * {
 *   "allow": true,
 *   "allowed_filtered_attributes": ["name", "topic"],
 *   "denied_filtered_attributes": ["internal_owner"],
 *   "masked_filtered_attributes": ["contact_email"]
 * }
 * </pre>
 *
 * <p>The shape is fixed so that a caller never has to know which rule answered: a whole-request
 * decision and a per-candidate decision are the same object, and a policy that only decides
 * allow/deny simply returns empty lists.
 *
 * <p>Deserialisation is deliberately lenient in one direction only. A bare {@code true}/{@code
 * false} is accepted and read as the verdict with empty lists, so a policy still returning OPA's
 * plain boolean result keeps working; anything else that is not an object yields DENY. Absent or
 * null lists become empty lists rather than null, so callers never null-check them.
 *
 * @param allow whether the action is permitted
 * @param allowedFilteredAttributes attributes the subject may see in full
 * @param deniedFilteredAttributes attributes that must be withheld entirely
 * @param maskedFilteredAttributes attributes that may be returned only in masked form
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DefaultPolicyDecisionOutput(
        @JsonProperty("allow") boolean allow,
        @JsonProperty("allowed_filtered_attributes") List<String> allowedFilteredAttributes,
        @JsonProperty("denied_filtered_attributes") List<String> deniedFilteredAttributes,
        @JsonProperty("masked_filtered_attributes") List<String> maskedFilteredAttributes) {

    /**
     * Request attribute the Policy Enforcement Point publishes its decision under, so the
     * decision survives into handler invocation and can be injected into a controller method.
     */
    public static final String REQUEST_ATTRIBUTE = DefaultPolicyDecisionOutput.class.getName();

    /** Permitted, with nothing filtered: what an unevaluated or switched-off decision yields. */
    public static final DefaultPolicyDecisionOutput ALLOW = new DefaultPolicyDecisionOutput(true);

    /** Refused, with nothing filtered: what an unreachable or unparsable PDP yields. */
    public static final DefaultPolicyDecisionOutput DENY = new DefaultPolicyDecisionOutput(false);

    public DefaultPolicyDecisionOutput {
        allowedFilteredAttributes = immutable(allowedFilteredAttributes);
        deniedFilteredAttributes = immutable(deniedFilteredAttributes);
        maskedFilteredAttributes = immutable(maskedFilteredAttributes);
    }

    /** A verdict with no attribute filtering. */
    public DefaultPolicyDecisionOutput(boolean allow) {
        this(allow, List.of(), List.of(), List.of());
    }

    /**
     * Reads a PDP result. Handles the object form, and the bare boolean a policy that decides
     * only allow/deny may still return; every other shape (a string, a number, an array, an
     * explicit null) is DENY, since a result that cannot be understood must not be read as
     * permission.
     *
     * @param result the {@code result} value of the PDP response
     * @return the decision it expresses
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static DefaultPolicyDecisionOutput fromJson(JsonNode result) {
        if (result == null || result.isBoolean()) {
            return result != null && result.booleanValue() ? ALLOW : DENY;
        }
        if (!result.isObject()) {
            return DENY;
        }
        return new DefaultPolicyDecisionOutput(
                result.path("allow").asBoolean(false),
                strings(result.path("allowed_filtered_attributes")),
                strings(result.path("denied_filtered_attributes")),
                strings(result.path("masked_filtered_attributes")));
    }

    /**
     * Narrows this decision by another, for the case where one request produces both a
     * whole-request decision and a decision per candidate resource: the action is permitted only
     * if both permit it, and the attribute lists are merged. Withholding wins over disclosure -
     * an attribute denied or masked by either decision is not left in the allowed list.
     *
     * @param other the decision to narrow this one by; null leaves this decision unchanged
     * @return the combined decision
     */
    public DefaultPolicyDecisionOutput combinedWith(DefaultPolicyDecisionOutput other) {
        if (other == null) {
            return this;
        }
        Set<String> denied = union(deniedFilteredAttributes, other.deniedFilteredAttributes);
        Set<String> masked = union(maskedFilteredAttributes, other.maskedFilteredAttributes);
        Set<String> allowed = union(allowedFilteredAttributes, other.allowedFilteredAttributes);
        allowed.removeAll(denied);
        allowed.removeAll(masked);
        return new DefaultPolicyDecisionOutput(
                allow && other.allow, List.copyOf(allowed), List.copyOf(denied), List.copyOf(masked));
    }

    /** The verdict as the enum the enforcement points log and branch on. */
    public PolicyDecision decision() {
        return PolicyDecision.of(allow);
    }

    private static Set<String> union(List<String> first, List<String> second) {
        Set<String> merged = new LinkedHashSet<>(first);
        merged.addAll(second);
        return merged;
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(node.size());
        node.forEach(element -> {
            if (!element.isNull()) {
                values.add(element.asText());
            }
        });
        return values;
    }
}
