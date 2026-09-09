/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data;

/**
 * The {@code attribute_scope.code} values this change resolves policy attributes for, on {@code
 * GET /api/v1/configuration/producer}: the producer itself, each allowed consumer, each of those
 * consumers' organisations, and each subscription ({@code product_consumer}). Not a general
 * registry of every {@code attribute_scope} row (e.g. {@code PRODUCT} is seeded but out of scope
 * for this change - see design.md).
 */
public enum PolicyAttributeScope {
    PRODUCER("PRODUCER"),
    CONSUMER("CONSUMER"),
    ORGANISATION("ORGANISATION"),
    SUBSCRIPTION("SUBSCRIPTION");

    private final String code;

    PolicyAttributeScope(String code) {
        this.code = code;
    }

    /** The {@code attribute_scope.code} value this constant corresponds to. */
    public String code() {
        return code;
    }
}
