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
 * The rule-specific part of a {@code product.view} decision
 * ({@code docker/opa/policies/product/view.rego}): the access level granted - {@code full},
 * {@code summary} or {@code none}. The same type also reads the product resource fallback's details
 * ({@code read}), which answers routes such as {@code browse}. The view endpoint declares it on
 * {@code @Policy} and receives {@code Optional<PolicyDecision<ProductViewPolicyDecisionDetails>>}.
 *
 * <p>A missing field reads as null rather than failing the decision. The rule's other details
 * ({@code withheld_fields}, {@code required_clearance}) land in {@link #additional()}.
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
