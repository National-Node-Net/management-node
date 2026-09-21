/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The database schema discovery's hand-written SQL runs against - the same one JPA uses
 * ({@code spring.jpa.properties.hibernate.default_schema}). Hibernate adds it to the queries it
 * generates; SQL written by hand has to add it itself.
 */
@Component
public class DiscoverySchema {

    private final String prefix;

    public DiscoverySchema(@Value("${spring.jpa.properties.hibernate.default_schema:}") String schema) {
        this.prefix = prefixFor(schema);
    }

    /** What to put in front of a table name: {@code "mn".}, or nothing when no schema is set. */
    public String prefix() {
        return prefix;
    }

    /** Quoted, because a schema name may need it ({@code management-node} does). */
    static String prefixFor(String schema) {
        if (!StringUtils.hasText(schema)) {
            return "";
        }
        if (!schema.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalStateException("Unsupported database schema name: " + schema);
        }
        return "\"" + schema + "\".";
    }
}
