/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.model.dto.product;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * One discovered product: what it is, who offers it, who consumes it and on what terms, and which
 * organisations are using it - each entity with its policy attributes.
 *
 * <p>Two endpoints return this type, and they fill different amounts of it:
 *
 * <ul>
 *   <li>{@code POST /api/v1/product/discover} returns a <b>summary</b> - {@code id}, {@code name},
 *       {@code description}, {@code type}, and an {@code organisation} carrying only its
 *       {@code key} and {@code name}. A search says which products exist, what they are and whose
 *       they are; everything else is absent, and is not read from the database either. Note the
 *       organisation's own {@code attributes} are among the things absent: a block can be returned
 *       without them;
 *   <li>{@code GET /api/v1/product/{productId}} returns the <b>whole</b> model below.
 * </ul>
 *
 * <p>What a particular caller receives is then decided by policy, which can only narrow further: a
 * member policy withholds is never read from the database, so it is simply absent here (null
 * members are not serialised). Absence therefore means one of two things - this endpoint does not
 * carry it, or policy withheld it - and in neither case was it read.
 *
 * <p>Attribute maps keep JSON types: a multi-valued attribute is a list, a number stays a number.
 *
 * @param id the identifier to pass to the view and subscribe endpoints
 * @param attributes the product's policy attributes, by name
 * @param organisation the organisation offering the product
 * @param producer the producer offering the product
 * @param consumers the consumers holding a grant, with the terms of each grant
 * @param subscribedBy the organisations using the product: those with at least one grant
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiscoveredProductDTO(
        Long id,
        String name,
        String description,
        String topic,
        String type,
        String source,
        Map<String, Object> attributes,
        Organisation organisation,
        Producer producer,
        List<Consumer> consumers,
        List<SubscribingOrganisation> subscribedBy) {

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Organisation(String key, String name, Map<String, Object> attributes) {}

    /** Connection details (host, port, client id) are deliberately not part of discovery. */
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Producer(String name, String description, Boolean active, Map<String, Object> attributes) {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Consumer(
            String name, Organisation organisation, Map<String, Object> attributes, Subscription subscription) {}

    /** The terms of one grant. Its delivery destination is deliberately not part of discovery. */
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Subscription(
            LocalDateTime grantedAt,
            BigDecimal validity,
            String scheduleType,
            String scheduleExpression,
            Map<String, Object> attributes) {}

    /**
     * @param consumers how many of the organisation's consumers hold a grant
     * @param since when the earliest of those grants was made
     */
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubscribingOrganisation(String key, String name, int consumers, LocalDateTime since) {}
}
