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
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionDetails;

/**
 * What a caller may see of the products they ask about: which products exist for them, which fields
 * and blocks are selected, and what is withheld. Every rule that answers about products returns
 * this much, so the same query machinery serves them all.
 *
 * <p>This is the <b>whole</b> contract, not the part two endpoints happen to share. Searching a set
 * of products and reading one of them are the same question asked of a different number of
 * products, so both rules answer it identically and both decisions are read here. The subclasses
 * add nothing to the contract:
 *
 * <ul>
 *   <li>{@link ProductDiscoveryPolicyDecisionDetails} - adds only how two decisions combine
 *       ({@code narrowedBy}) and the {@code fields()}/{@code attributes()} groupings;
 *   <li>{@link ProductViewPolicyDecisionDetails} - adds only {@code accessLevel()}, which is
 *       reported and never enforced.
 * </ul>
 *
 * <p>Keeping it whole and in one place is the point: an endpoint cannot acquire its own idea of what
 * is withheld, the two cannot drift, and adding a third needs no new enforcement code.
 *
 * <p><b>Everything missing reads in the withholding direction</b>, which is the safety property the
 * whole design rests on: a missing list is empty, a missing row filter matches <em>nothing</em>
 * rather than everything, and a missing sensitivity flag masks. A rule that forgets a field
 * therefore narrows access instead of widening it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public abstract class ProductPolicyContractDetails extends PolicyDecisionDetails {

    @JsonProperty("row_filter")
    private FilterNode rowFilter;

    @JsonProperty("visible_fields")
    private List<String> visibleFields;

    @JsonProperty("masked_filtered_fields")
    private List<String> maskedFilteredFields;

    @JsonProperty("masked_filtered_attributes")
    private List<String> maskedFilteredAttributes;

    @JsonProperty("unmask_when")
    private List<UnmaskRule> unmaskWhen;

    @JsonProperty("mask_sensitive_attributes")
    private Boolean maskSensitiveAttributes;

    @JsonProperty("obligations")
    private List<String> obligations;

    @JsonProperty("evaluation")
    private String evaluation;

    @JsonProperty("allowed_filtered_fields")
    private List<String> allowedFilteredFields;

    @JsonProperty("denied_filtered_fields")
    private List<String> deniedFilteredFields;

    @JsonProperty("allowed_filtered_attributes")
    private List<String> allowedFilteredAttributes;

    @JsonProperty("denied_filtered_attributes")
    private List<String> deniedFilteredAttributes;

    @JsonProperty("text_search_fields")
    private List<String> textSearchFields;

    @JsonProperty("max_page_size")
    private Integer maxPageSize;

    protected ProductPolicyContractDetails() {}

    /**
     * The condition a product must satisfy to be returned at all; it becomes the query's
     * {@code WHERE} clause. Matches nothing when the rule gave none - never "no filter".
     */
    public FilterNode rowFilter() {
        return rowFilter == null ? FilterNode.DENY_ALL : rowFilter;
    }

    /** Fields and blocks that may be selected, as qualified names; never null. */
    public List<String> visibleFields() {
        return orEmpty(visibleFields);
    }

    /** Product fields and blocks that must be withheld from results; never null. */
    public List<String> maskedFilteredFields() {
        return orEmpty(maskedFilteredFields);
    }

    /** Policy attributes whose values must be withheld from results; never null. */
    public List<String> maskedFilteredAttributes() {
        return orEmpty(maskedFilteredAttributes);
    }

    /** Masked names shown after all on the products matching a condition; never null. */
    public List<UnmaskRule> unmaskWhen() {
        return unmaskWhen == null ? List.of() : List.copyOf(unmaskWhen);
    }

    /**
     * Whether attributes flagged {@code sensitive} in the attribute catalogue are withheld. True
     * unless the rule explicitly says otherwise.
     */
    public boolean maskSensitiveAttributes() {
        return !Boolean.FALSE.equals(maskSensitiveAttributes);
    }

    /** Duties the service must fulfil to honour the decision; never null. */
    public List<String> obligations() {
        return orEmpty(obligations);
    }

    /**
     * Which level the decision was taken at - {@code request}, or {@code candidate} when the rule
     * was asked about one product. Null when the rule did not say.
     */
    public String evaluation() {
        return evaluation;
    }

    /** Product fields the caller may filter, sort or text-search on; never null. */
    public List<String> allowedFilteredFields() {
        return orEmpty(allowedFilteredFields);
    }

    /** Product fields the caller asked to use but may not; never null. */
    public List<String> deniedFilteredFields() {
        return orEmpty(deniedFilteredFields);
    }

    /** Policy attributes the caller may filter, sort or text-search on; never null. */
    public List<String> allowedFilteredAttributes() {
        return orEmpty(allowedFilteredAttributes);
    }

    /** Policy attributes the caller asked to use but may not; never null. */
    public List<String> deniedFilteredAttributes() {
        return orEmpty(deniedFilteredAttributes);
    }

    /** The fields a free-text term is matched against; never null. */
    public List<String> textSearchFields() {
        return orEmpty(textSearchFields);
    }

    /** The largest page the caller may ask for; null when the rule did not say. */
    public Integer maxPageSize() {
        return maxPageSize;
    }

    // ---------------------------------------------------------------------------------------
    // For subclasses building a combined instance; the wire form is read by Jackson.
    // ---------------------------------------------------------------------------------------

    protected void rowFilter(FilterNode value) {
        this.rowFilter = value;
    }

    protected void visibleFields(List<String> value) {
        this.visibleFields = value;
    }

    protected void maskedFilteredFields(List<String> value) {
        this.maskedFilteredFields = value;
    }

    protected void maskedFilteredAttributes(List<String> value) {
        this.maskedFilteredAttributes = value;
    }

    protected void unmaskWhen(List<UnmaskRule> value) {
        this.unmaskWhen = value;
    }

    protected void maskSensitiveAttributes(Boolean value) {
        this.maskSensitiveAttributes = value;
    }

    protected void obligations(List<String> value) {
        this.obligations = value;
    }

    protected void evaluation(String value) {
        this.evaluation = value;
    }

    protected void allowedFilteredFields(List<String> value) {
        this.allowedFilteredFields = value;
    }

    protected void deniedFilteredFields(List<String> value) {
        this.deniedFilteredFields = value;
    }

    protected void allowedFilteredAttributes(List<String> value) {
        this.allowedFilteredAttributes = value;
    }

    protected void deniedFilteredAttributes(List<String> value) {
        this.deniedFilteredAttributes = value;
    }

    protected void textSearchFields(List<String> value) {
        this.textSearchFields = value;
    }

    protected void maxPageSize(Integer value) {
        this.maxPageSize = value;
    }

    /** A list as callers read it: never null, never modifiable. */
    protected static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /**
     * Names that are masked in general but shown on the products matching {@code when} - the way a
     * rule says "an organisation sees who uses its own products". The condition is evaluated by the
     * database, per product, in the same query that finds the products.
     *
     * @param names masked field or block names to show
     * @param when the condition a product must satisfy for them to be shown
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UnmaskRule(@JsonProperty("names") List<String> names, @JsonProperty("when") FilterNode when) {
        public UnmaskRule {
            names = orEmpty(names);
            when = when == null ? FilterNode.DENY_ALL : when;
        }
    }
}
