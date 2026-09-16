/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.policy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionDetails;

/**
 * Opts a controller method into policy enforcement. A handler without it is never sent to the
 * PDP: enforcement is declared at development time, per endpoint, rather than inferred from URL
 * patterns in configuration.
 *
 * <p>{@code resource} and {@code action} become {@code input.resource.kind} and
 * {@code input.action}, and together select the rule the PDP evaluates - see
 * {@code docs/POLICY_ENFORCEMENT.md} for how an action with no dedicated rule is resolved.
 *
 * <p>Both must be lower-case identifiers ({@code [a-z][a-z0-9_]*}) because they name a Rego
 * package, and {@code fallback} is reserved for the fallback rules. Violations fail application
 * startup.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Policy {

    /** The resource kind the endpoint acts on, e.g. {@code product}. */
    String resource();

    /** The action the endpoint performs on that resource, e.g. {@code subscribe}. */
    String action();

    /**
     * The {@link PolicyDecisionDetails} subclass the policy's {@code details} object is read into.
     * Each rule may return its own details shape; the default keeps them in the generic form. A
     * result whose details cannot be read as this type is treated as DENY.
     *
     * <p>A handler receiving the decision declares the same type in its parameter, e.g.
     * {@code Optional<PolicyDecision<ProductSubscriptionPolicyDecisionDetails>>}; a parameter that
     * could not hold this type fails startup.
     */
    Class<? extends PolicyDecisionDetails> details() default PolicyDecisionDetails.class;
}
