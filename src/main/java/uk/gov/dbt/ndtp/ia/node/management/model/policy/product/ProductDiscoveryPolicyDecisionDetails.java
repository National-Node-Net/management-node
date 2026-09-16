/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.policy.product;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionDetails;

/**
 * The rule-specific part of a {@code product.discover} decision
 * ({@code docker/opa/policies/product/discover.rego}). The discover endpoint declares it on
 * {@code @Policy} and receives {@code Optional<PolicyDecision<ProductDiscoveryPolicyDecisionDetails>>};
 * per-candidate evaluation reads the same type, so the request and candidate decisions combine.
 *
 * <p>Attribute filtering is discovery's own term rather than part of the generic decision envelope,
 * so the allowed, denied and masked attribute lists are read from here.
 *
 * <p>A missing field reads as null (an empty list for the lists) rather than failing the decision;
 * fields the rule adds later land in {@link #additional()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ProductDiscoveryPolicyDecisionDetails extends PolicyDecisionDetails {

    /** {@link #evaluation()} for the decision on the discover request as a whole. */
    public static final String EVALUATION_REQUEST = "request";

    /** {@link #evaluation()} for the decision on one candidate product. */
    public static final String EVALUATION_CANDIDATE = "candidate";

    @JsonProperty("evaluation")
    private String evaluation;

    @JsonProperty("permitted_nationalities")
    private List<String> permittedNationalities;

    @JsonProperty("excluded_classifications")
    private List<String> excludedClassifications;

    @JsonProperty("allowed_filtered_attributes")
    private List<String> allowedFilteredAttributes;

    @JsonProperty("denied_filtered_attributes")
    private List<String> deniedFilteredAttributes;

    @JsonProperty("masked_filtered_attributes")
    private List<String> maskedFilteredAttributes;

    public ProductDiscoveryPolicyDecisionDetails() {}

    /**
     * @param evaluation which level the decision was taken at: {@value #EVALUATION_REQUEST} or
     *     {@value #EVALUATION_CANDIDATE}
     * @param permittedNationalities organisation nationalities the rule lets discover
     * @param excludedClassifications product classifications the rule never discloses
     */
    public ProductDiscoveryPolicyDecisionDetails(
            String evaluation, List<String> permittedNationalities, List<String> excludedClassifications) {
        this.evaluation = evaluation;
        this.permittedNationalities = permittedNationalities;
        this.excludedClassifications = excludedClassifications;
    }

    /**
     * @param evaluation which level the decision was taken at: {@value #EVALUATION_REQUEST} or
     *     {@value #EVALUATION_CANDIDATE}
     * @param permittedNationalities organisation nationalities the rule lets discover
     * @param excludedClassifications product classifications the rule never discloses
     * @param allowedFilteredAttributes product attributes the caller may see in full
     * @param deniedFilteredAttributes product attributes that must be withheld entirely
     * @param maskedFilteredAttributes product attributes that may be returned only in masked form
     */
    public ProductDiscoveryPolicyDecisionDetails(
            String evaluation,
            List<String> permittedNationalities,
            List<String> excludedClassifications,
            List<String> allowedFilteredAttributes,
            List<String> deniedFilteredAttributes,
            List<String> maskedFilteredAttributes) {
        this(evaluation, permittedNationalities, excludedClassifications);
        this.allowedFilteredAttributes = allowedFilteredAttributes;
        this.deniedFilteredAttributes = deniedFilteredAttributes;
        this.maskedFilteredAttributes = maskedFilteredAttributes;
    }

    /** Which level the decision was taken at; null when the rule did not say. */
    public String evaluation() {
        return evaluation;
    }

    /** Organisation nationalities the rule lets discover; never null. */
    public List<String> permittedNationalities() {
        return permittedNationalities == null ? List.of() : List.copyOf(permittedNationalities);
    }

    /** Product classifications the rule never discloses; never null. */
    public List<String> excludedClassifications() {
        return excludedClassifications == null ? List.of() : List.copyOf(excludedClassifications);
    }

    /** Product attributes the caller may see in full; never null. */
    public List<String> allowedFilteredAttributes() {
        return allowedFilteredAttributes == null ? List.of() : List.copyOf(allowedFilteredAttributes);
    }

    /** Product attributes that must be withheld entirely; never null. */
    public List<String> deniedFilteredAttributes() {
        return deniedFilteredAttributes == null ? List.of() : List.copyOf(deniedFilteredAttributes);
    }

    /** Product attributes that may be returned only in masked form; never null. */
    public List<String> maskedFilteredAttributes() {
        return maskedFilteredAttributes == null ? List.of() : List.copyOf(maskedFilteredAttributes);
    }

    /**
     * Narrows request-level details by a candidate's. The candidate's evaluation, nationalities and
     * classifications win when it carries any details (it is the more specific answer), but the
     * attribute lists are merged so neither decision can widen what the other withholds: anything
     * denied or masked by either is denied or masked, and is not left in the allowed list.
     */
    @Override
    public ProductDiscoveryPolicyDecisionDetails narrowedBy(PolicyDecisionDetails narrower) {
        if (!(narrower instanceof ProductDiscoveryPolicyDecisionDetails other) || other.isEmpty()) {
            return this;
        }
        Set<String> denied = union(deniedFilteredAttributes(), other.deniedFilteredAttributes());
        Set<String> masked = union(maskedFilteredAttributes(), other.maskedFilteredAttributes());
        Set<String> allowed = union(allowedFilteredAttributes(), other.allowedFilteredAttributes());
        allowed.removeAll(denied);
        allowed.removeAll(masked);
        ProductDiscoveryPolicyDecisionDetails combined = new ProductDiscoveryPolicyDecisionDetails(
                other.evaluation,
                other.permittedNationalities,
                other.excludedClassifications,
                List.copyOf(allowed),
                List.copyOf(denied),
                List.copyOf(masked));
        other.additional().forEach(combined::putAdditional);
        return combined;
    }

    private static Set<String> union(List<String> first, List<String> second) {
        Set<String> merged = new LinkedHashSet<>(first);
        merged.addAll(second);
        return merged;
    }
}
