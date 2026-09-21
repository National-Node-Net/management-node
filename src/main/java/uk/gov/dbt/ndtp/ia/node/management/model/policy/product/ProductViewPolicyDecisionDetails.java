/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.policy.product;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * The rule-specific part of a {@code product.view} decision
 * ({@code docker/opa/policies/product/view.rego}): what the caller may see of the one product they
 * asked for. The view endpoint declares it on {@code @Policy} and receives
 * {@code Optional<PolicyDecision<ProductViewPolicyDecisionDetails>>}.
 *
 * <p>Almost all of it is inherited: reading one product is a search constrained to that product, so
 * the contract in {@link ProductPolicyContractDetails} - the row filter, the visible fields, the
 * masking and the obligations - is the same contract discovery is given, and is applied by the same
 * code. Only {@link #accessLevel()} is this rule's own.
 *
 * <p>The row filter matters more here than it looks. Without it the endpoint would hand any product
 * to any caller who passed the clearance gate, including products that caller could not discover -
 * so the filter is what stops an id being a way around discovery.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ProductViewPolicyDecisionDetails extends ProductPolicyContractDetails {

    /** {@link #accessLevel()} when the caller may read the product. */
    public static final String ACCESS_LEVEL_READ = "read";

    @JsonProperty("access_level")
    private String accessLevel;

    public ProductViewPolicyDecisionDetails() {}

    /** @param accessLevel the access level the rule granted, e.g. {@value #ACCESS_LEVEL_READ} */
    public ProductViewPolicyDecisionDetails(String accessLevel) {
        this.accessLevel = accessLevel;
    }

    /**
     * How much of the product the rule says this caller sees - {@code full}, {@code summary} or
     * {@code none}; null when the rule did not say.
     *
     * <p><b>Reported, not enforced.</b> What is actually withheld is said in
     * {@link #maskedFilteredFields()} and {@link #maskedFilteredAttributes()}, which the query is
     * built from. Nothing branches on this value: two mechanisms for "show less" is how they come
     * to disagree. It is carried so a client can show the caller which tier they were granted.
     */
    public String accessLevel() {
        return accessLevel;
    }
}
