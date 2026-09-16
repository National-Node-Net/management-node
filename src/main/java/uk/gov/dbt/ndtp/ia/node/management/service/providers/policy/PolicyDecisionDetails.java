/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * The rule-specific part of a {@link PolicyDecision} - the {@code details} object a Rego rule
 * returns alongside the generic envelope.
 *
 * <p>Used as it is, this is the generic form: every field the rule returned is kept, in order, and
 * read through {@link #additional()}. A rule whose details an endpoint acts on is given its own
 * subclass, declaring those fields as typed properties, and the endpoint names that subclass in
 * {@code @Policy(details = ...)} and in its {@code Optional<PolicyDecision<...>>} parameter:
 *
 * <pre>
 * &#64;JsonIgnoreProperties(ignoreUnknown = true)
 * public class ProductViewPolicyDecisionDetails extends PolicyDecisionDetails {
 *     &#64;JsonProperty("access_level")
 *     private String accessLevel;
 * }
 * </pre>
 *
 * <p>Fields a subclass declares are bound to it; anything else the rule returns still lands in
 * {@link #additional()}, so a rule can grow its details without breaking the reader. Every
 * subclass must be concrete and have a no-argument constructor: details are bound by Jackson, and
 * a decision that no rule answered carries {@link #empty(Class) empty} details of the declared
 * type rather than null. Both are checked at startup.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@EqualsAndHashCode
@ToString
public class PolicyDecisionDetails {

    private final Map<String, Object> additional = new LinkedHashMap<>();

    public PolicyDecisionDetails() {}

    /**
     * Generic details holding the given fields.
     *
     * @param fields the rule's details object, as parsed; null is treated as empty
     */
    public PolicyDecisionDetails(Map<String, ?> fields) {
        if (fields != null) {
            additional.putAll(fields);
        }
    }

    /**
     * The fields no typed property of this class claimed - for the generic form, every field the
     * rule returned.
     *
     * @return an unmodifiable, insertion-ordered view
     */
    @JsonAnyGetter
    public Map<String, Object> additional() {
        return Collections.unmodifiableMap(additional);
    }

    @JsonAnySetter
    void putAdditional(String name, Object value) {
        additional.put(name, value);
    }

    /**
     * Details of {@code type} with no fields set: what a decision no rule answered carries.
     *
     * @param type the details type
     * @return a new instance, made through its no-argument constructor
     * @throws IllegalStateException when the type cannot be instantiated that way - a programming
     *     error, which startup validation of {@code @Policy} exists to catch earlier
     */
    public static <D extends PolicyDecisionDetails> D empty(Class<D> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (NoSuchMethodException
                | InstantiationException
                | IllegalAccessException
                | InvocationTargetException
                | RuntimeException e) {
            throw new IllegalStateException(
                    "Policy decision details type " + type.getName() + " cannot be instantiated with no arguments", e);
        }
    }
}
