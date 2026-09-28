/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto.network;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

/**
 * The whole network in one document: who exists, and what joins them.
 *
 * Deliberately five flat lists rather than a nested tree. A graph is not a tree - a product has
 * one producer but many consumers, and a consumer reaches products across several organisations -
 * so any nesting would have to repeat entities and force the reader to de-duplicate them. Flat
 * lists plus foreign keys is the shape a graph renderer actually wants: each list becomes nodes,
 * and the keys become edges.
 *
 * The edges a caller can draw from it:
 *
 * <ul>
 *   <li>{@code producer.organisationKey} and {@code consumer.organisationKey} - belongs to
 *   <li>{@code product.producerId} - published by
 *   <li>{@code subscription.consumerId} to {@code subscription.productId} - uses
 * </ul>
 *
 * Ids are the database's own, so a caller can join these lists to anything else the API returns -
 * a product's id here is the id {@code GET /api/v1/product/{productId}} takes.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
        description =
                "Organisations, their producers and consumers, the products published, and the subscriptions between them")
public record NetworkMapDTO(
        @Schema(description = "Every organisation that owns a producer or a consumer") List<Organisation> organisations,
        @Schema(description = "Every producer, with the organisation it belongs to") List<Producer> producers,
        @Schema(description = "Every consumer, with the organisation it belongs to") List<Consumer> consumers,
        @Schema(description = "Every product, with the producer that publishes it") List<Product> products,
        @Schema(description = "Every grant joining a consumer to a product") List<Subscription> subscriptions,
        @Schema(description = "Counts of what the document contains") Totals totals) {

    /** Empty rather than null throughout, so a caller never has to null-check a list. */
    public NetworkMapDTO {
        organisations = organisations == null ? List.of() : List.copyOf(organisations);
        producers = producers == null ? List.of() : List.copyOf(producers);
        consumers = consumers == null ? List.of() : List.copyOf(consumers);
        products = products == null ? List.of() : List.copyOf(products);
        subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
    }

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Organisation(
            @Schema(example = "1") Long id,
            @Schema(description = "Stable, human-readable identifier", example = "ENV") String key,
            @Schema(example = "Environment Agency") String name) {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Producer(
            Long id,
            @Schema(example = "ENV-PRODUCER-1") String name,
            String description,
            @Schema(description = "Whether the producer is currently serving") Boolean active,
            @Schema(description = "The organisation this producer belongs to", example = "ENV")
                    String organisationKey) {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Consumer(
            Long id,
            @Schema(example = "ENV-CONSUMER-1") String name,
            @Schema(description = "The organisation this consumer belongs to", example = "ENV") String organisationKey,
            @Schema(description = "Whether this is its organisation's default consumer") Boolean defaultConsumer,
            String scheduleType,
            String scheduleExpression) {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Product(
            Long id,
            @Schema(example = "FloodRiskMapZones") String name,
            String description,
            String topic,
            @Schema(description = "The product type's name", example = "topic") String type,
            String source,
            @Schema(description = "The producer that publishes this product") Long producerId,
            @Schema(description = "The organisation that producer belongs to", example = "ENV")
                    String organisationKey) {}

    /**
     * One {@code product_consumer} grant.
     *
     * Carries its own id as well as both ends, because the terms below - the schedule, the
     * validity - belong to the grant rather than to either the product or the consumer.
     */
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Subscription(
            Long id,
            Long productId,
            Long consumerId,
            LocalDateTime grantedAt,
            BigDecimal validity,
            String scheduleType,
            String scheduleExpression) {}

    @Builder
    @Schema(description = "How many of each kind the document contains")
    public record Totals(int organisations, int producers, int consumers, int products, int subscriptions) {}
}
