/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The bound parameters of one SQL statement. Every value that reaches the database - from a
 * caller or from a policy - goes through {@link #bind(Object)}, which hands back the placeholder
 * to put in the SQL text. Values are therefore never concatenated into a statement.
 */
public final class SqlParameters {

    private final Map<String, Object> values = new LinkedHashMap<>();

    /**
     * Binds a value under a fresh name.
     *
     * @param value a scalar, or a collection for an {@code IN (...)} list
     * @return the placeholder for the SQL text, e.g. {@code :p3}
     */
    public String bind(Object value) {
        String name = "p" + values.size();
        values.put(name, value);
        return ":" + name;
    }

    /** The bound values by parameter name, in the order they were bound. */
    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(values);
    }
}
