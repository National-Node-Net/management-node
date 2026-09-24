/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.policy.product;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionDetails;

/**
 * The rule-specific part of a {@code product.subscribe} decision
 * ({@code docker/opa/policies/product/subscribe.rego}) - the terms the subscription is held to.
 * The subscribe endpoint declares it on {@code @Policy} and receives
 * {@code Optional<PolicyDecision<ProductSubscriptionPolicyDecisionDetails>>}.
 *
 * <p>A missing field reads as null (an empty list for the schedule types) rather than failing the
 * decision; fields the rule adds later land in {@link #additional()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ProductSubscriptionPolicyDecisionDetails extends PolicyDecisionDetails {

    @JsonProperty("requires_approval")
    private Boolean requiresApproval;

    @JsonProperty("max_validity_days")
    private Integer maxValidityDays;

    @JsonProperty("permitted_schedule_types")
    private List<String> permittedScheduleTypes;

    /**
     * How long this organisation may hold this product, in days, as the rule worked it out from
     * the caller's and the product's attributes. Distinct from {@link #maxValidityDays()}, which
     * is the ceiling the caller's purpose allows: this is the grant actually being made.
     */
    @JsonProperty("validity_days")
    private Integer validityDays;

    public ProductSubscriptionPolicyDecisionDetails() {}

    /**
     * @param requiresApproval whether the grant must be approved before it takes effect
     * @param maxValidityDays the longest validity the rule allows for the grant
     * @param permittedScheduleTypes the schedule types the rule allows
     */
    public ProductSubscriptionPolicyDecisionDetails(
            Boolean requiresApproval, Integer maxValidityDays, List<String> permittedScheduleTypes) {
        this(requiresApproval, maxValidityDays, permittedScheduleTypes, null);
    }

    public ProductSubscriptionPolicyDecisionDetails(
            Boolean requiresApproval,
            Integer maxValidityDays,
            List<String> permittedScheduleTypes,
            Integer validityDays) {
        this.requiresApproval = requiresApproval;
        this.maxValidityDays = maxValidityDays;
        this.permittedScheduleTypes = permittedScheduleTypes;
        this.validityDays = validityDays;
    }

    /** Whether the grant must be approved before it takes effect; null when the rule did not say. */
    public Boolean requiresApproval() {
        return requiresApproval;
    }

    /** The longest validity, in days, the rule allows for the grant; null when the rule did not say. */
    public Integer maxValidityDays() {
        return maxValidityDays;
    }

    /** The schedule types the rule allows; never null. */
    public List<String> permittedScheduleTypes() {
        return permittedScheduleTypes == null ? List.of() : List.copyOf(permittedScheduleTypes);
    }

    /**
     * The validity to record on the grant, in days; null when the rule did not say. A service must
     * decide for itself what an unstated validity means rather than inventing a number here.
     */
    public Integer validityDays() {
        return validityDays;
    }
}
