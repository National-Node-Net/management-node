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
 * ({@code docker/opa/policies/product/discover.rego}): the search contract for the caller. The
 * discover endpoint declares it on {@code @Policy} and receives
 * {@code Optional<PolicyDecision<ProductDiscoveryPolicyDecisionDetails>>}; per-candidate evaluation
 * reads the same type, so the request and candidate decisions combine.
 *
 * <p>Filtering is described twice, because a search layer handles the two differently:
 *
 * <ul>
 *   <li><b>fields</b> - properties of the product itself, as the API names them ({@code name},
 *       {@code topic}, {@code source}, ...), filtered as columns;
 *   <li><b>attributes</b> - policy attributes stored against the product
 *       ({@code identifiability}, {@code quality_designation}, ...), filtered through the attribute
 *       table.
 * </ul>
 *
 * <p>For each, the rule says what the caller may filter on, what they asked for but may not, and
 * what must be masked in results. The rest of the contract ({@code row_filter},
 * {@code max_page_size}, {@code obligations}) is read through {@link #additional()}.
 *
 * <p>A missing list reads as empty rather than failing the decision.
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

    @JsonProperty("allowed_filtered_fields")
    private List<String> allowedFilteredFields;

    @JsonProperty("denied_filtered_fields")
    private List<String> deniedFilteredFields;

    @JsonProperty("masked_filtered_fields")
    private List<String> maskedFilteredFields;

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
     * @param fields filtering and masking of product fields
     * @param attributes filtering and masking of product policy attributes
     */
    public ProductDiscoveryPolicyDecisionDetails(String evaluation, Filtering fields, Filtering attributes) {
        this.evaluation = evaluation;
        this.allowedFilteredFields = fields.allowed();
        this.deniedFilteredFields = fields.denied();
        this.maskedFilteredFields = fields.masked();
        this.allowedFilteredAttributes = attributes.allowed();
        this.deniedFilteredAttributes = attributes.denied();
        this.maskedFilteredAttributes = attributes.masked();
    }

    /** Which level the decision was taken at; null when the rule did not say. */
    public String evaluation() {
        return evaluation;
    }

    /** Product fields the caller may filter on; never null. */
    public List<String> allowedFilteredFields() {
        return orEmpty(allowedFilteredFields);
    }

    /** Product fields the caller asked to filter on but may not; never null. */
    public List<String> deniedFilteredFields() {
        return orEmpty(deniedFilteredFields);
    }

    /** Product fields that must be masked in results; never null. */
    public List<String> maskedFilteredFields() {
        return orEmpty(maskedFilteredFields);
    }

    /** Product policy attributes the caller may filter on; never null. */
    public List<String> allowedFilteredAttributes() {
        return orEmpty(allowedFilteredAttributes);
    }

    /** Product policy attributes the caller asked to filter on but may not; never null. */
    public List<String> deniedFilteredAttributes() {
        return orEmpty(deniedFilteredAttributes);
    }

    /** Product policy attributes whose values must be masked in results; never null. */
    public List<String> maskedFilteredAttributes() {
        return orEmpty(maskedFilteredAttributes);
    }

    /** Filtering and masking of product fields, as one value. */
    public Filtering fields() {
        return new Filtering(allowedFilteredFields(), deniedFilteredFields(), maskedFilteredFields());
    }

    /** Filtering and masking of product policy attributes, as one value. */
    public Filtering attributes() {
        return new Filtering(allowedFilteredAttributes(), deniedFilteredAttributes(), maskedFilteredAttributes());
    }

    /**
     * Narrows request-level details by a candidate's. The candidate's evaluation wins when it carries
     * any details (it is the more specific answer), but fields and attributes are each merged so
     * neither decision can widen what the other withholds.
     */
    @Override
    public ProductDiscoveryPolicyDecisionDetails narrowedBy(PolicyDecisionDetails narrower) {
        if (!(narrower instanceof ProductDiscoveryPolicyDecisionDetails other) || other.isEmpty()) {
            return this;
        }
        ProductDiscoveryPolicyDecisionDetails combined = new ProductDiscoveryPolicyDecisionDetails(
                other.evaluation,
                fields().narrowedBy(other.fields()),
                attributes().narrowedBy(other.attributes()));
        other.additional().forEach(combined::putAdditional);
        return combined;
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /**
     * What a caller may filter on, asked for but may not, and must see masked - for either fields or
     * attributes.
     *
     * @param allowed names the caller may filter on
     * @param denied names the caller asked to filter on but may not
     * @param masked names that must be masked in results
     */
    public record Filtering(List<String> allowed, List<String> denied, List<String> masked) {

        public Filtering {
            allowed = orEmpty(allowed);
            denied = orEmpty(denied);
            masked = orEmpty(masked);
        }

        /**
         * Merges two answers, withholding winning over disclosure: anything denied or masked by
         * either is denied or masked, and is not left in the allowed list.
         */
        public Filtering narrowedBy(Filtering other) {
            Set<String> denied = union(this.denied, other.denied);
            Set<String> masked = union(this.masked, other.masked);
            Set<String> allowed = union(this.allowed, other.allowed);
            allowed.removeAll(denied);
            allowed.removeAll(masked);
            return new Filtering(List.copyOf(allowed), List.copyOf(denied), List.copyOf(masked));
        }

        private static Set<String> union(List<String> first, List<String> second) {
            Set<String> merged = new LinkedHashSet<>(first);
            merged.addAll(second);
            return merged;
        }
    }
}
