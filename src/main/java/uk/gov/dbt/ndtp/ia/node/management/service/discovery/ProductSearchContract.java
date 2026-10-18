/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterTarget;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductPolicyContractDetails;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductPolicyContractDetails.UnmaskRule;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeScopeCode;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;

/**
 * What one caller may do in one discovery search. Everything else in this package asks the
 * contract rather than the policy decision, so there is a single place that says what a decision
 * means - and what the absence of one means.
 *
 * <p>There are two kinds:
 *
 * <ul>
 *   <li>{@link #enforcing enforcing} - built from the {@code product.discover} decision. Anything
 *       the decision does not grant is withheld;
 *   <li>{@link #open open} - used when policy enforcement is switched off
 *       ({@code application.opa.enabled=false}) and no decision exists. Every product is
 *       returned, every field is visible and filterable, and nothing is masked.
 * </ul>
 *
 * <p>Names are <em>qualified</em>: a product's own field or attribute is written bare
 * ({@code name}, {@code identifiability}); anything else carries its entity as a prefix
 * ({@code organisation.key}, {@code consumer.operating_remit}).
 */
public final class ProductSearchContract {

    /** In a masked attribute list, hides every attribute of a scope: {@code consumer.*}. */
    public static final String ALL_ATTRIBUTES = "*";

    /**
     * The largest page a caller may ask for when nothing else sets a limit - that is, when the
     * decision returned no {@code max_page_size}, or when policy enforcement is switched off.
     *
     * <p>It is a constant rather than a setting because it is a bound on one query's cost, not a
     * policy: who may see how much is the PDP's answer, and a rule that wants a different cap
     * returns one. Leaving it configurable would offer an operator a second, silent way to change
     * what a caller gets, which is exactly what this design keeps in one place.
     */
    public static final int DEFAULT_MAX_PAGE_SIZE = 100;

    private final boolean enforced;
    private final FilterNode rowFilter;
    private final Set<String> filterableFields;
    private final Set<String> filterableAttributes;
    private final Set<String> maskedFields;
    private final Set<String> maskedAttributes;
    private final Set<String> visibleFields;
    private final Set<String> textSearchFields;
    private final List<UnmaskRule> unmaskRules;
    private final boolean maskSensitiveAttributes;
    private final int maxPageSize;
    private final List<String> obligations;

    private ProductSearchContract(
            boolean enforced, ProductPolicyContractDetails details, FilterNode rowFilter, int maxPageSize) {
        this.enforced = enforced;
        this.rowFilter = rowFilter;
        this.filterableFields = Set.copyOf(details.allowedFilteredFields());
        this.filterableAttributes = Set.copyOf(details.allowedFilteredAttributes());
        this.textSearchFields = Set.copyOf(details.textSearchFields());
        this.maskedFields = Set.copyOf(details.maskedFilteredFields());
        this.maskedAttributes = Set.copyOf(details.maskedFilteredAttributes());
        this.visibleFields = Set.copyOf(details.visibleFields());
        this.unmaskRules = details.unmaskWhen();
        this.maskSensitiveAttributes = enforced && details.maskSensitiveAttributes();
        this.maxPageSize = maxPageSize;
        this.obligations = details.obligations();
    }

    /**
     * The contract a policy decision grants.
     *
     * @param decision the decision taken for the request - a search's or a single product's
     */
    public static ProductSearchContract enforcing(PolicyDecision<? extends ProductPolicyContractDetails> decision) {
        ProductPolicyContractDetails details = decision.details();
        // A denied request never reaches the query; should one, it finds nothing.
        FilterNode rowFilter = decision.allow() ? details.rowFilter() : FilterNode.DENY_ALL;
        Integer decided = details.maxPageSize();
        int maxPageSize = decided == null || decided < 1 ? DEFAULT_MAX_PAGE_SIZE : decided;
        return new ProductSearchContract(true, details, rowFilter, maxPageSize);
    }

    /** The contract when policy enforcement is switched off: nothing is restricted. */
    public static ProductSearchContract open() {
        return new ProductSearchContract(
                false, new ProductDiscoveryPolicyDecisionDetails(), FilterNode.ALLOW_ALL, DEFAULT_MAX_PAGE_SIZE);
    }

    /** Whether a policy decision stands behind this contract. */
    public boolean isEnforced() {
        return enforced;
    }

    /** The condition every returned product must satisfy. */
    public FilterNode rowFilter() {
        return rowFilter;
    }

    /**
     * Whether the caller may filter or sort on a target. Something the caller may not see can
     * never be filtered on, whatever the allowed lists say - otherwise a filter would reveal it.
     */
    public boolean mayFilterOn(FilterTarget target) {
        if (!enforced) {
            return true;
        }
        String name = target.qualifiedName();
        return target.field()
                ? filterableFields.contains(name) && !maskedFields.contains(name)
                : filterableAttributes.contains(name) && !maskedAttributes.contains(name);
    }

    /**
     * Whether a field or block is shown on every product.
     *
     * @param name a product field name or a {@link ProductBlock} name
     */
    public boolean isVisible(String name) {
        return !enforced || (visibleFields.contains(name) && !maskedFields.contains(name));
    }

    /** Whether one member of a visible block is withheld, e.g. {@code organisation.name}. */
    public boolean isMasked(String qualifiedFieldName) {
        return enforced && maskedFields.contains(qualifiedFieldName);
    }

    /** Masked names that are shown after all on the products matching a condition. */
    public List<UnmaskRule> unmaskRules() {
        return unmaskRules;
    }

    /** The fields a free-text term is matched against; empty when the caller may not text-search. */
    public List<ProductField> textSearchFields() {
        return Arrays.stream(ProductField.values())
                .filter(ProductField::isTextSearchable)
                .filter(field ->
                        !enforced || (textSearchFields.contains(field.apiName()) && isVisible(field.apiName())))
                .toList();
    }

    /** Whether attributes flagged {@code sensitive} in the attribute catalogue are withheld. */
    public boolean masksSensitiveAttributes() {
        return maskSensitiveAttributes;
    }

    /**
     * The attribute names withheld within one scope, bare. Contains {@link #ALL_ATTRIBUTES} when
     * every attribute of the scope is withheld.
     */
    public Set<String> maskedAttributeNames(PolicyAttributeScopeCode scope) {
        String prefix =
                scope == PolicyAttributeScopeCode.PRODUCT ? "" : scope.code().toLowerCase(Locale.ROOT) + ".";
        return maskedAttributes.stream()
                .filter(name -> prefix.isEmpty() ? !name.contains(".") : name.startsWith(prefix))
                .map(name -> name.substring(prefix.length()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public int maxPageSize() {
        return maxPageSize;
    }

    public List<String> obligations() {
        return obligations;
    }

    public List<String> filterableFields() {
        return sorted(filterableFields);
    }

    public List<String> filterableAttributes() {
        return sorted(filterableAttributes);
    }

    public List<String> maskedFields() {
        return sorted(maskedFields);
    }

    public List<String> maskedAttributes() {
        return sorted(maskedAttributes);
    }

    private static List<String> sorted(Set<String> names) {
        return names.stream().sorted().toList();
    }
}
