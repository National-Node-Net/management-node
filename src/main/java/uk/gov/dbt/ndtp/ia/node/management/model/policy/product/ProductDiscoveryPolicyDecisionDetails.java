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
 * The rule-specific part of a {@code product.discover} decision
 * ({@code docker/opa/policies/product/discover.rego}). The discover endpoint declares it on
 * {@code @Policy} and receives {@code Optional<PolicyDecision<ProductDiscoveryPolicyDecisionDetails>>};
 * per-candidate evaluation reads the same type, so the request and candidate decisions combine.
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
}
