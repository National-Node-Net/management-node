/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.model.policy.filter;

/**
 * What a filter or a sort key points at: a product <b>field</b> (a column, such as {@code type}) or
 * a policy <b>attribute</b> (such as {@code identifiability}), of the product or of the
 * organisation that owns it.
 *
 * @param scope the entity the name belongs to; the product when null
 * @param field whether {@code name} is a field rather than a policy attribute
 * @param name the field or attribute name, bare (never qualified)
 */
public record FilterTarget(FilterScope scope, boolean field, String name) {

    public FilterTarget {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A filter needs a field or an attribute");
        }
        scope = scope == null ? FilterScope.PRODUCT : scope;
    }

    public static FilterTarget ofField(String name) {
        return new FilterTarget(FilterScope.PRODUCT, true, name);
    }

    public static FilterTarget ofAttribute(String name) {
        return new FilterTarget(FilterScope.PRODUCT, false, name);
    }

    /**
     * Reads a name that may be qualified, as a policy writes it: {@code organisation.key} is the
     * field {@code key} in the organisation scope.
     */
    public static FilterTarget parse(FilterScope scope, boolean field, String name) {
        String organisationPrefix = FilterScope.ORGANISATION.wireName() + ".";
        if (scope == null && name != null && name.startsWith(organisationPrefix)) {
            return new FilterTarget(FilterScope.ORGANISATION, field, name.substring(organisationPrefix.length()));
        }
        return new FilterTarget(scope, field, name);
    }

    /** The name as policy lists refer to it: {@code name}, or {@code organisation.name}. */
    public String qualifiedName() {
        return scope.qualify(name);
    }
}
