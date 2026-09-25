/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data;

/**
 * The {@code policy_attribute_scope.code} values policy attributes are resolved for on {@code
 * GET /api/v1/configuration/producer}: the producer itself, each of its products, each allowed
 * consumer, the organisations those belong to, and each subscription ({@code product_consumer}).
 *
 * <p>This now covers every seeded {@code policy_attribute_scope} row; {@link #code()} is verified
 * against the seeded codes in {@code PolicyAttributeScopeCodeTest}.
 */
public enum PolicyAttributeScopeCode {
    PRODUCER("PRODUCER"),
    PRODUCT("PRODUCT"),
    CONSUMER("CONSUMER"),
    ORGANISATION("ORGANISATION"),
    SUBSCRIPTION("SUBSCRIPTION");

    private final String code;

    PolicyAttributeScopeCode(String code) {
        this.code = code;
    }

    /** The {@code policy_attribute_scope.code} value this constant corresponds to. */
    public String code() {
        return code;
    }
}
