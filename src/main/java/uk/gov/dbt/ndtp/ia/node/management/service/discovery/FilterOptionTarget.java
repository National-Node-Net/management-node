/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import java.util.List;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.ComparisonOperator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterTarget;

/**
 * One filter a caller may be offered in the product catalogue's filter panel: a product field or a
 * policy attribute, with the type and operators it supports, and whether it is worth listing
 * distinct values for.
 *
 * @param key the identity the API and the caller use for this option: {@code field:<apiName>} for
 *     a field, {@code attribute:<scope wireName>.<name>} for an attribute
 * @param target what {@link uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchCriteriaFactory}
 *     and the row filter mean by this option
 * @param field the {@link ProductField} this option is, or null for an attribute
 * @param label the caller-facing name
 * @param dataType {@code TEXT}, {@code NUMBER} or {@code BOOLEAN}
 * @param multiValued whether the target can carry more than one value at once
 * @param operators the comparisons this target supports, in {@link ComparisonOperator} declaration
 *     order
 * @param defaultOperator the operator applied when the caller gives none
 * @param sortable whether the caller may sort on this target
 * @param listsValues whether the filter panel is worth listing distinct values for
 */
public record FilterOptionTarget(
        String key,
        FilterTarget target,
        ProductField field,
        String label,
        String dataType,
        boolean multiValued,
        List<ComparisonOperator> operators,
        ComparisonOperator defaultOperator,
        boolean sortable,
        boolean listsValues) {}
