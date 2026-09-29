/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.policy.product;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
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
 * what must be masked in results. The rest of the contract shapes the query itself:
 *
 * <ul>
 *   <li>{@link #rowFilter()} - the condition every returned product must satisfy; it becomes the
 *       query's {@code WHERE} clause;
 *   <li>{@link #visibleFields()} - the fields and blocks that may be selected at all;
 *   <li>{@link #unmaskWhen()} - masked names that are shown after all on the products matching a
 *       condition (an organisation's own products, say);
 *   <li>{@link #textSearchFields()}, {@link #maxPageSize()}, {@link #obligations()} and
 *       {@link #maskSensitiveAttributes()}.
 * </ul>
 *
 * <p>The contract itself lives on {@link ProductPolicyContractDetails}, because
 * {@code policies.product.view} returns exactly the same one - a view is this search constrained to
 * a single product. What is added here is only how two decisions combine, and the groupings
 * {@link #fields()} and {@link #attributes()}.
 *
 * <p>Everything missing reads in the withholding direction: a missing list is empty, a missing row
 * filter matches nothing, and a missing sensitivity flag masks.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ProductDiscoveryPolicyDecisionDetails extends ProductPolicyContractDetails {

    /** {@link #evaluation()} for the decision on the discover request as a whole. */
    public static final String EVALUATION_REQUEST = "request";

    /** {@link #evaluation()} for the decision on one candidate product. */
    public static final String EVALUATION_CANDIDATE = "candidate";

    public ProductDiscoveryPolicyDecisionDetails() {}

    /**
     * @param evaluation which level the decision was taken at: {@value #EVALUATION_REQUEST} or
     *     {@value #EVALUATION_CANDIDATE}
     * @param fields filtering and masking of product fields
     * @param attributes filtering and masking of product policy attributes
     */
    public ProductDiscoveryPolicyDecisionDetails(String evaluation, Filtering fields, Filtering attributes) {
        evaluation(evaluation);
        allowedFilteredFields(fields.allowed());
        deniedFilteredFields(fields.denied());
        allowedFilteredAttributes(attributes.allowed());
        deniedFilteredAttributes(attributes.denied());
        maskedFilteredFields(fields.masked());
        maskedFilteredAttributes(attributes.masked());
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
                other.evaluation(),
                fields().narrowedBy(other.fields()),
                attributes().narrowedBy(other.attributes()));
        combined.rowFilter(FilterNode.Group.and(List.of(rowFilter(), other.rowFilter())));
        combined.visibleFields(intersection(visibleFields(), other.visibleFields()));
        combined.textSearchFields(intersection(textSearchFields(), other.textSearchFields()));
        combined.unmaskWhen(unmaskWhen().equals(other.unmaskWhen()) ? unmaskWhen() : List.of());
        combined.maskSensitiveAttributes(maskSensitiveAttributes() || other.maskSensitiveAttributes());
        combined.maxPageSize(smaller(maxPageSize(), other.maxPageSize()));
        combined.obligations(List.copyOf(Filtering.union(obligations(), other.obligations())));
        other.additional().forEach(combined::putAdditional);
        return combined;
    }

    private static List<String> intersection(List<String> first, List<String> second) {
        return first.stream().filter(second::contains).toList();
    }

    private static Integer smaller(Integer first, Integer second) {
        if (first == null || second == null) {
            return first == null ? second : first;
        }
        return Math.min(first, second);
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
