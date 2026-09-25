/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.model.dto.product;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Builder;

/**
 * Response for {@code POST /v1/product/discover}.
 *
 * @param products the page of products the caller may discover that match the criteria; never null
 * @param page where this page sits in the whole result
 * @param policy what the policy allowed this caller, so a client can build its search form from
 *     it; absent when policy enforcement is switched off
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductDiscoveryResponseDTO(List<DiscoveredProductDTO> products, Page page, Policy policy) {

    public ProductDiscoveryResponseDTO {
        products = products == null ? List.of() : List.copyOf(products);
    }

    /**
     * Where this page sits in the whole result.
     *
     * <p>{@code size} and {@code numberOfElements} answer different questions and are easy to
     * confuse: {@code size} is the page size that was <em>applied</em> (what the caller asked for,
     * clamped to what policy allows), while {@code numberOfElements} is how many products this
     * response actually carries. They differ on the last page, and whenever fewer products match
     * than a page holds.
     *
     * @param number zero-based page number
     * @param size the page size applied, after clamping to the policy's maximum
     * @param numberOfElements how many products are in {@code products} on this page
     * @param totalElements how many products match in all; counts only products the caller may see
     * @param totalPages how many pages of {@code size} those matches fill
     */
    public record Page(int number, int size, int numberOfElements, long totalElements, int totalPages) {

        /**
         * @param numberOfElements how many products the page actually carries; take it from the
         *     products themselves rather than assuming a full page
         */
        public static Page of(int number, int size, int numberOfElements, long totalElements) {
            // A page holding no rows is a misconfigured page size rather than a page of
            // everything, so it is reported as no pages instead of dividing by zero.
            int totalPages = size < 1 ? 0 : (int) ((totalElements + size - 1) / size);
            return new Page(number, size, numberOfElements, totalElements, totalPages);
        }
    }

    /**
     * @param filterableFields fields the caller may filter and sort on
     * @param filterableAttributes policy attributes the caller may filter and sort on
     * @param textSearchFields fields a free-text term is matched against
     * @param maskedFields fields and blocks withheld from results
     * @param maskedAttributes attributes withheld from results
     * @param maxPageSize the largest page the caller may ask for
     * @param obligations duties attached to the decision
     */
    @Builder
    public record Policy(
            List<String> filterableFields,
            List<String> filterableAttributes,
            List<String> textSearchFields,
            List<String> maskedFields,
            List<String> maskedAttributes,
            int maxPageSize,
            List<String> obligations) {}
}
