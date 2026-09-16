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
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionDetails;

/**
 * The rule-specific part of a {@code product.view} decision. No rule is dedicated to viewing, so
 * it is answered by the product resource fallback ({@code docker/opa/policies/product/fallback.rego}),
 * whose details carry the access level granted. The view endpoint declares it on {@code @Policy} and
 * receives {@code Optional<PolicyDecision<ProductViewPolicyDecisionDetails>>}; a dedicated
 * {@code product.view} rule written later keeps this contract or extends it.
 *
 * <p>A missing field reads as null rather than failing the decision; fields a rule adds later land
 * in {@link #additional()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ProductViewPolicyDecisionDetails extends PolicyDecisionDetails {

    /** {@link #accessLevel()} when the caller may read the product. */
    public static final String ACCESS_LEVEL_READ = "read";

    @JsonProperty("access_level")
    private String accessLevel;

    public ProductViewPolicyDecisionDetails() {}

    /** @param accessLevel the access level the rule granted, e.g. {@value #ACCESS_LEVEL_READ} */
    public ProductViewPolicyDecisionDetails(String accessLevel) {
        this.accessLevel = accessLevel;
    }

    /** The access level the rule granted; null when the rule did not say. */
    public String accessLevel() {
        return accessLevel;
    }
}
