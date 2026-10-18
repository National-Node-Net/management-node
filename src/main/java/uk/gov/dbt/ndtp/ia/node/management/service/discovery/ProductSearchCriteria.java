/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import java.util.List;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;

/**
 * What a caller asked for, after validation: every filter and sort key here exists, is permitted
 * by the {@link ProductSearchContract}, and has a usable operator and values. Built only by
 * {@link ProductSearchCriteriaFactory}.
 *
 * @param text the free-text term; null when none was given
 * @param filters comparisons that must all hold
 * @param sort sort keys, most significant first; never empty
 * @param page zero-based page number
 * @param size page size, already clamped to the contract's maximum
 */
public record ProductSearchCriteria(
        String text, List<FilterNode.Comparison> filters, List<SortKey> sort, int page, int size) {

    public ProductSearchCriteria {
        filters = List.copyOf(filters);
        sort = List.copyOf(sort);
    }

    public boolean hasText() {
        return text != null;
    }

    public long offset() {
        return (long) page * size;
    }

    /**
     * @param field the field to sort by; null when sorting by an attribute
     * @param scope the entity an attribute sort key belongs to
     * @param attribute the attribute to sort by; null when sorting by a field
     */
    public record SortKey(ProductField field, FilterScope scope, String attribute, boolean descending) {

        public static SortKey byField(ProductField field, boolean descending) {
            return new SortKey(field, FilterScope.PRODUCT, null, descending);
        }

        public static SortKey byAttribute(FilterScope scope, String attribute, boolean descending) {
            return new SortKey(null, scope, attribute, descending);
        }
    }
}
