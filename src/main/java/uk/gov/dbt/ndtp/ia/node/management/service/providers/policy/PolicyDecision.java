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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The decision document every policy returns, whatever it is deciding about: a generic envelope
 * that every rule fills the same way, plus a rule-specific {@code details} object of type
 * {@code D}.
 *
 * <pre>
 * {
 *   "allow": true,
 *   "reasons": ["dispatch.resource_fallback"],
 *   "policy": {"id": "product.fallback", "version": "policies.product.fallback/1.0.0",
 *              "resolution": "resource_fallback"},
 *   "details": {"access_level": "read"}
 * }
 * </pre>
 *
 * <p>The envelope is fixed so that a caller never has to know which rule answered: a
 * whole-request decision and a per-candidate decision are the same object, and a policy that only
 * decides allow/deny simply returns no reasons and empty details. {@code details} is the one part whose shape
 * belongs to the rule. It is parsed as the generic {@link PolicyDecisionDetails} and converted to
 * the subclass the caller declared by {@link PolicyDecisionClient#evaluate(PolicyInput, Class)} -
 * so an endpoint reads, for example, a {@code PolicyDecision<ProductSubscriptionPolicyDecisionDetails>}
 * and never casts.
 *
 * <p>Deserialisation is deliberately lenient in one direction only. A bare {@code true}/{@code
 * false} is accepted and read as the verdict with nothing else, so a policy still returning OPA's
 * plain boolean result keeps working; anything else that is not an object yields DENY. Absent or
 * null parts are normalised - {@code reasons} to an empty list, {@code policy} to {@link PolicyProvenance#NONE},
 * {@code details} to empty details - so callers never null-check them.
 *
 * @param <D> the type of the rule-specific details
 * @param allow whether the action is permitted
 * @param reasons stable codes explaining the decision, e.g. {@code organisation.missing}
 * @param policy which rule answered and how it was selected
 * @param details the rule-specific part of the decision
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicyDecision<D extends PolicyDecisionDetails>(
        @JsonProperty("allow") boolean allow,
        @JsonProperty("reasons") List<String> reasons,
        @JsonProperty("policy") PolicyProvenance policy,
        @JsonProperty("details") D details) {

    /**
     * Request attribute the Policy Enforcement Point publishes its decision under, so the
     * decision survives into handler invocation and can be injected into a controller method.
     */
    public static final String REQUEST_ATTRIBUTE = PolicyDecision.class.getName();

    /** Prefixes of reasons about policy wiring rather than the caller; see {@link #callerReasons()}. */
    private static final List<String> INTERNAL_REASON_PREFIXES = List.of("dispatch.", "policy.");

    /** Reason given when a rule's details cannot be read into the type the caller declared. */
    public static final String REASON_DETAILS_UNREADABLE = "policy.details_unreadable";

    /** Permitted, with nothing else: what an unevaluated or switched-off decision yields. */
    public static final PolicyDecision<PolicyDecisionDetails> ALLOW = of(true, PolicyDecisionDetails.class);

    /** Refused, with nothing else: what an unreachable or unparsable PDP yields. */
    public static final PolicyDecision<PolicyDecisionDetails> DENY = of(false, PolicyDecisionDetails.class);

    // Only ever used to turn an already-parsed JSON tree into plain maps, lists and scalars, so it
    // needs none of the application mapper's configuration.
    private static final ObjectMapper TREE_READER = new ObjectMapper();

    public PolicyDecision {
        reasons = immutable(reasons);
        policy = policy == null ? PolicyProvenance.NONE : policy;
        Objects.requireNonNull(details, "details");
    }

    /**
     * A verdict with no reasons or provenance, and empty details of
     * {@code detailsType}.
     *
     * @param allow whether the action is permitted
     * @param detailsType the type of the (empty) details
     * @return the decision
     */
    public static <D extends PolicyDecisionDetails> PolicyDecision<D> of(boolean allow, Class<D> detailsType) {
        return new PolicyDecision<>(allow, List.of(), PolicyProvenance.NONE, PolicyDecisionDetails.empty(detailsType));
    }

    /**
     * A refusal no rule answered, carrying the one reason it was refused.
     *
     * @param reason stable reason code
     * @return a DENY with that reason and nothing else
     */
    public static PolicyDecision<PolicyDecisionDetails> deny(String reason) {
        return deny(reason, PolicyProvenance.NONE, PolicyDecisionDetails.class);
    }

    /**
     * A refusal carrying one reason and the provenance of the rule it concerns, with empty details
     * of {@code detailsType}.
     *
     * @param reason stable reason code
     * @param policy provenance of the rule the refusal concerns; null for none
     * @param detailsType the type of the (empty) details
     * @return the refusal
     */
    public static <D extends PolicyDecisionDetails> PolicyDecision<D> deny(
            String reason, PolicyProvenance policy, Class<D> detailsType) {
        return new PolicyDecision<>(false, List.of(reason), policy, PolicyDecisionDetails.empty(detailsType));
    }

    /**
     * Reads a PDP result. Handles the object form, and the bare boolean a policy that decides
     * only allow/deny may still return; every other shape (a string, a number, an array, an
     * explicit null) is DENY, since a result that cannot be understood must not be read as
     * permission. A {@code details} value that is present but not an object breaks the contract
     * every rule shares, so it too is DENY - keeping the provenance, so the broken rule can be found.
     *
     * @param result the {@code result} value of the PDP response
     * @return the decision it expresses, with generic details
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static PolicyDecision<PolicyDecisionDetails> fromJson(JsonNode result) {
        if (result == null || result.isBoolean()) {
            return result != null && result.booleanValue() ? ALLOW : DENY;
        }
        if (!result.isObject()) {
            return DENY;
        }
        PolicyProvenance policy = provenance(result.path("policy"));
        JsonNode details = result.path("details");
        if (!details.isMissingNode() && !details.isNull() && !details.isObject()) {
            return deny(REASON_DETAILS_UNREADABLE, policy, PolicyDecisionDetails.class);
        }
        return new PolicyDecision<>(
                result.path("allow").asBoolean(false),
                strings(result.path("reasons")),
                policy,
                new PolicyDecisionDetails(details.isObject() ? detailsMap(details) : Map.of()));
    }

    /**
     * This decision with its details replaced, e.g. by their typed form.
     *
     * @param details the new details
     * @return the same envelope carrying {@code details}
     */
    public <E extends PolicyDecisionDetails> PolicyDecision<E> withDetails(E details) {
        return new PolicyDecision<>(allow, reasons, policy, details);
    }

    /**
     * Narrows this decision by another of the same details type, for the case where one request
     * produces both a whole-request decision and a decision per candidate resource:
     *
     * <ul>
     *   <li>the action is permitted only if both permit it;
     *   <li>reasons are the union of both, de-duplicated and sorted so the result is stable;
     *   <li>provenance is the narrower decision's ({@code other}) when it has one - it is the more
     *       specific answer - and this decision's otherwise; it counts as absent when it is
     *       {@link PolicyProvenance#NONE};
     *   <li>details are combined by {@link PolicyDecisionDetails#narrowedBy(PolicyDecisionDetails)},
     *       so a details type that carries terms of its own (such as discovery's attribute lists)
     *       decides how they merge.
     * </ul>
     *
     * @param other the decision to narrow this one by; null leaves this decision unchanged
     * @return the combined decision
     */
    // narrowedBy's contract is to return an instance of the receiver's own type, so the result is a D.
    @SuppressWarnings("unchecked")
    public PolicyDecision<D> combinedWith(PolicyDecision<D> other) {
        if (other == null) {
            return this;
        }
        Set<String> mergedReasons = new TreeSet<>(reasons);
        mergedReasons.addAll(other.reasons);
        return new PolicyDecision<>(
                allow && other.allow,
                List.copyOf(mergedReasons),
                PolicyProvenance.NONE.equals(other.policy) ? policy : other.policy,
                (D) details.narrowedBy(other.details));
    }

    /**
     * The reasons a refused caller may be shown: those describing the caller and its request, such as
     * {@code organisation.clearance_insufficient} or {@code schedule.type_not_permitted}.
     *
     * <p>Reasons starting {@code dispatch.} or {@code policy.} describe how policy is wired - which rule
     * answered, a missing route, an unreadable rule - rather than anything the caller can change, so
     * they are left out and stay in the log.
     *
     * @return the caller-facing reasons, in their original order
     */
    public List<String> callerReasons() {
        return reasons.stream()
                .filter(reason -> INTERNAL_REASON_PREFIXES.stream().noneMatch(reason::startsWith))
                .toList();
    }

    /** The verdict as the enum the enforcement points log and branch on. */
    public PolicyVerdict verdict() {
        return PolicyVerdict.of(allow);
    }

    private static PolicyProvenance provenance(JsonNode node) {
        if (!node.isObject()) {
            return PolicyProvenance.NONE;
        }
        return new PolicyProvenance(text(node.get("id")), text(node.get("version")), text(node.get("resolution")));
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailsMap(JsonNode node) {
        return TREE_READER.convertValue(node, LinkedHashMap.class);
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
