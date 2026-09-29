/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.model.policy.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * The entity a filtered field or attribute belongs to. A name outside the product scope is written
 * qualified wherever policy or the API refers to it by one string: {@code organisation.key}.
 */
public enum FilterScope {
    /** The product itself; names are written bare. */
    PRODUCT("PRODUCT"),
    /** The organisation that owns the product. */
    ORGANISATION("ORGANISATION");

    private final String attributeScopeCode;

    FilterScope(String attributeScopeCode) {
        this.attributeScopeCode = attributeScopeCode;
    }

    /** The {@code policy_attribute_scope.code} attributes of this scope are stored under. */
    public String attributeScopeCode() {
        return attributeScopeCode;
    }

    /** {@code name} as one string: bare for a product, {@code organisation.name} otherwise. */
    public String qualify(String name) {
        return this == PRODUCT ? name : wireName() + "." + name;
    }

    @JsonValue
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static FilterScope fromWireName(String value) {
        for (FilterScope scope : values()) {
            if (scope.wireName().equalsIgnoreCase(value)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Unsupported scope '" + value + "'; use 'product' or 'organisation'");
    }
}
