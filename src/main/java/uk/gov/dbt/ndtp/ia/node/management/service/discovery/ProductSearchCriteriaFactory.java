/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import uk.gov.dbt.ndtp.ia.node.management.exception.AccessRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.InvalidSearchCriteriaException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryFilterDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoverySortDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.ComparisonOperator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterTarget;

/**
 * Turns the request body into {@link ProductSearchCriteria}, refusing what is malformed
 * ({@code 400}) and what the contract does not permit ({@code 403}, naming what was refused).
 *
 * <p>Permission is checked before existence: a name the caller may not use is refused the same
 * way whether or not it exists, so the endpoint cannot be used to probe the vocabulary.
 */
@Component
public class ProductSearchCriteriaFactory {

    static final String REASON_FIELD = "filter.field_not_permitted:";
    static final String REASON_ATTRIBUTE = "filter.attribute_not_permitted:";
    static final String REASON_SORT = "sort.not_permitted:";
    static final String REASON_TEXT = "text.not_permitted";

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_VALUE_LENGTH = 255;

    /**
     * @param request the request body; null when the caller sent none
     * @param contract what the caller may do
     * @param sensitiveAttributes names of the attributes the catalogue flags sensitive; consulted
     *     only when the contract withholds them
     */
    public ProductSearchCriteria create(
            ProductDiscoveryRequestDTO request, ProductSearchContract contract, Set<String> sensitiveAttributes) {
        ProductDiscoveryRequestDTO body = request == null ? ProductDiscoveryRequestDTO.EMPTY : request;
        List<String> refusals = new ArrayList<>();

        List<FilterNode.Comparison> filters = new ArrayList<>();
        for (ProductDiscoveryFilterDTO filter : body.filters()) {
            FilterTarget target = target(filter.scope(), filter.field(), filter.attribute(), "filter");
            if (permitted(target, contract, sensitiveAttributes)) {
                filters.add(comparison(target, filter));
            } else {
                refusals.add((target.field() ? REASON_FIELD : REASON_ATTRIBUTE) + target.qualifiedName());
            }
        }

        List<ProductSearchCriteria.SortKey> sort = new ArrayList<>();
        for (ProductDiscoverySortDTO key : body.sort()) {
            FilterTarget target = target(key.scope(), key.field(), key.attribute(), "sort key");
            if (permitted(target, contract, sensitiveAttributes)) {
                sort.add(sortKey(target, key.descending()));
            } else {
                refusals.add(REASON_SORT + target.qualifiedName());
            }
        }
        if (sort.isEmpty()) {
            sort.add(ProductSearchCriteria.SortKey.byField(ProductField.NAME, false));
        }

        String text = StringUtils.hasText(body.text()) ? body.text().trim() : null;
        if (text != null && contract.textSearchFields().isEmpty()) {
            refusals.add(REASON_TEXT);
        }

        if (!refusals.isEmpty()) {
            throw new AccessRejectedException(
                    "Access denied by policy", refusals, UUID.randomUUID().toString());
        }
        int page = body.page() == null ? 0 : body.page();
        int size = Math.min(body.size() == null ? DEFAULT_PAGE_SIZE : body.size(), contract.maxPageSize());
        return new ProductSearchCriteria(text, filters, sort, page, size);
    }

    /**
     * What a filter or a sort key points at. The name may be written either way policy writes it -
     * qualified ({@code organisation.key}) or with an explicit {@code scope} - and both must mean the
     * same thing, so it is read with {@link FilterTarget#parse}: a qualified name that the caller did
     * not scope carries its own scope.
     *
     * <p>Reading it as a bare name instead would pass the permission check, because the allowed lists
     * are spelt qualified, and then look for the name in the <em>product</em> scope - an attribute of
     * the owning organisation would silently match nothing rather than be refused or answered.
     */
    private static FilterTarget target(FilterScope scope, String field, String attribute, String what) {
        if (StringUtils.hasText(field) == StringUtils.hasText(attribute)) {
            throw new InvalidSearchCriteriaException("A " + what + " names exactly one of 'field' and 'attribute'");
        }
        boolean isField = StringUtils.hasText(field);
        return FilterTarget.parse(scope, isField, isField ? field : attribute);
    }

    private static boolean permitted(
            FilterTarget target, ProductSearchContract contract, Set<String> sensitiveAttributes) {
        boolean sensitive =
                !target.field() && contract.masksSensitiveAttributes() && sensitiveAttributes.contains(target.name());
        return !sensitive && contract.mayFilterOn(target);
    }

    private static FilterNode.Comparison comparison(FilterTarget target, ProductDiscoveryFilterDTO filter) {
        ProductField field = target.field() ? filterableField(target) : null;
        ComparisonOperator operator = filter.operator() != null ? filter.operator() : defaultOperator(field, filter);
        String name = target.qualifiedName();

        if (!operator.accepts(filter.values().size())) {
            throw new InvalidSearchCriteriaException("Operator '" + operator.wireName() + "' on '" + name
                    + "' cannot take " + filter.values().size() + " value(s)");
        }
        for (Object value : filter.values()) {
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new InvalidSearchCriteriaException(
                        "The values of '" + name + "' must be text, numbers or booleans");
            }
            if (String.valueOf(value).length() > MAX_VALUE_LENGTH) {
                throw new InvalidSearchCriteriaException(
                        "A value of '" + name + "' is longer than " + MAX_VALUE_LENGTH + " characters");
            }
            if (operator.isOrdering() && !(value instanceof Number)) {
                throw new InvalidSearchCriteriaException(
                        "Operator '" + operator.wireName() + "' on '" + name + "' needs a number");
            }
        }
        if (field != null && (operator.isOrdering() || operator == ComparisonOperator.ALL_OF)) {
            throw new InvalidSearchCriteriaException(
                    "Operator '" + operator.wireName() + "' does not apply to the field '" + name + "'");
        }
        return new FilterNode.Comparison(target, operator, filter.values());
    }

    /** Text fields are searched by substring, as a person would expect; everything else exactly. */
    private static ComparisonOperator defaultOperator(ProductField field, ProductDiscoveryFilterDTO filter) {
        if (filter.values().size() != 1) {
            return ComparisonOperator.IN;
        }
        return field != null && field.isTextSearchable() ? ComparisonOperator.CONTAINS : ComparisonOperator.EQ;
    }

    private static ProductSearchCriteria.SortKey sortKey(FilterTarget target, boolean descending) {
        if (!target.field()) {
            return ProductSearchCriteria.SortKey.byAttribute(target.scope(), target.name(), descending);
        }
        ProductField field = ProductField.find(target.scope(), target.name())
                .filter(ProductField::isSortable)
                .orElseThrow(() -> new InvalidSearchCriteriaException(
                        "Products cannot be sorted by '" + target.qualifiedName() + "'"));
        return ProductSearchCriteria.SortKey.byField(field, descending);
    }

    private static ProductField filterableField(FilterTarget target) {
        return ProductField.find(target.scope(), target.name())
                .filter(ProductField::isFilterable)
                .orElseThrow(() -> new InvalidSearchCriteriaException(
                        "Products cannot be filtered on '" + target.qualifiedName() + "'"));
    }
}
