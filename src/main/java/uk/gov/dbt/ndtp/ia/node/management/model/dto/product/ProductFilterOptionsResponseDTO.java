/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.model.dto.product;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Builder;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;

/**
 * Response for {@code POST /api/v1/product/discover/filter}: the product catalogue's filter
 * panel, built from the same search contract {@code POST /api/v1/product/discover} applies.
 *
 * @param totalElements how many products match with no filter applied; the panel's baseline
 * @param textSearch whether a free-text term is permitted, and which fields it is matched against
 * @param filters every filter the caller is offered, in the order {@code FilterOptionTargets}
 *     decides
 * @param policy what the policy allowed this caller; absent when policy enforcement is switched
 *     off
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductFilterOptionsResponseDTO(
        long totalElements,
        TextSearch textSearch,
        List<FilterOption> filters,
        ProductDiscoveryResponseDTO.Policy policy) {

    /**
     * @param enabled whether a free-text term is permitted at all
     * @param fields the fields a term is matched against
     */
    public record TextSearch(boolean enabled, List<String> fields) {}

    /**
     * One filter the caller may apply: its identity, what it targets, how it is typed and
     * compared, and - when worth listing - the distinct values it holds with their counts.
     *
     * @param key the identity the caller uses for this option, as {@link
     *     uk.gov.dbt.ndtp.ia.node.management.service.discovery.FilterOptionTarget#key()}
     * @param target what this option targets, in the same shape a discover criterion names it
     * @param label the caller-facing name
     * @param dataType {@code TEXT}, {@code NUMBER} or {@code BOOLEAN}
     * @param multiValued whether the target can carry more than one value at once
     * @param operators the comparisons this target supports, by wire name
     * @param defaultOperator the operator applied when the caller gives none
     * @param sortable whether the caller may sort on this target
     * @param values the distinct values this filter holds, with their counts; absent when not
     *     worth listing
     * @param missingCount how many products carry no value at all for this target; absent when
     *     zero
     * @param truncated whether {@code values} was cut at the cap; absent when it was not
     */
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FilterOption(
            String key,
            Target target,
            String label,
            String dataType,
            boolean multiValued,
            List<String> operators,
            String defaultOperator,
            boolean sortable,
            List<Value> values,
            Long missingCount,
            Boolean truncated) {}

    /**
     * What a filter option targets, in the same shape a discover criterion names it: exactly one
     * of {@code field} and {@code attribute} is present. Copied verbatim into a discover
     * {@code filters[]} entry. Fields carry only {@code field}.
     *
     * @param field the field's qualified API name; null for an attribute
     * @param scope the entity an attribute belongs to; null for a field
     * @param attribute the attribute's bare name; null for a field
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Target(String field, FilterScope scope, String attribute) {}

    /**
     * One distinct value a filter holds, and how many products carry it.
     *
     * @param value a String, or a Boolean for a BOOLEAN target
     * @param count how many matching products carry this value
     * @param selected whether the caller's current criteria already selected this value
     */
    public record Value(Object value, long count, boolean selected) {}
}
