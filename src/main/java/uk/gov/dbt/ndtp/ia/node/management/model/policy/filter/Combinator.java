/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.model.policy.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** How the nodes of a {@link FilterNode.Group} are joined. */
public enum Combinator {
    AND,
    OR;

    @JsonValue
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static Combinator fromWireName(String value) {
        for (Combinator combinator : values()) {
            if (combinator.wireName().equalsIgnoreCase(value)) {
                return combinator;
            }
        }
        throw new IllegalArgumentException("Unsupported combinator '" + value + "'; use 'and' or 'or'");
    }
}
