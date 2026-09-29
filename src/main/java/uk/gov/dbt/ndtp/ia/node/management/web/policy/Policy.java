/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
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

    /**
     * Whether to read the entity this request names and send it to the PDP as
     * {@code resource.id}, {@code resource.fields} and {@code resource.attributes}.
     *
     * <p>Off by default, because reading an entity costs queries and most rules decide about a
     * kind of thing rather than one entity: discovery asks which products a caller may see, and
     * view delegates the per-product decision to the row filter compiled into its SQL. Neither
     * reads the entity in Rego, so neither should pay to load it.
     *
     * <p>Turn it on for an endpoint whose rule needs the entity's own facts. Subscription is the
     * case today: it works out how long a grant may last from the product's identifiability and
     * quality, and no SQL predicate does that work.
     *
     * <p>Where the id comes from is not declared here. {@link
     * uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyResourceIdExtractor} finds
     * it by the same convention for every endpoint, whether the endpoint carries it in the path or
     * in the body.
     */
    boolean loadResource() default false;
}
