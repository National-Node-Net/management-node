/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import java.util.EnumSet;
import java.util.Set;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeScopeCode;

/**
 * How much of a product an endpoint asks for. It is the one thing that differs between searching
 * for products and reading one: policy answers both identically, and this says what the endpoint
 * does with that answer.
 *
 * <p>It can only ever <b>narrow</b>. A projection is intersected with what the policy decision
 * permits, never added to it - so asking for a field policy withholds still yields nothing, and no
 * projection can widen access.
 *
 * <p>Like masking, it is applied by <em>not selecting</em>: a field outside the projection is left
 * out of the {@code SELECT} list and a block outside it is never queried, so the value does not
 * enter the JVM at all. There is no trimming step afterwards that a later change could forget.
 */
public enum ProductProjection {

    /**
     * A search result: enough to recognise a product, to see whose it is, and to ask for it by id.
     * Reading the rest of the product is what {@code GET /api/v1/product/{productId}} is for, so a
     * search carries no producer, no consumers, nobody who subscribes and <b>no policy attributes
     * of any entity</b> - and queries for none of them.
     *
     * <p>The owning organisation is the exception among the blocks: a caller choosing which of a
     * page of products to open needs to know who offers each one, and {@code organisation.key} and
     * {@code organisation.name} are columns of a join the search already makes, so carrying them
     * costs no extra statement. That organisation's <em>attributes</em> would, so they are left to
     * the view.
     *
     * <p>{@code id} is not listed because it is always selected: it is how a caller asks for the
     * rest.
     */
    SUMMARY(
            EnumSet.of(
                    ProductField.NAME,
                    ProductField.DESCRIPTION,
                    ProductField.TYPE,
                    ProductField.ORGANISATION_KEY,
                    ProductField.ORGANISATION_NAME),
            EnumSet.of(ProductBlock.ORGANISATION),
            EnumSet.noneOf(PolicyAttributeScopeCode.class)),

    /** Everything the object model can carry, subject to the decision. */
    FULL(
            EnumSet.allOf(ProductField.class),
            EnumSet.allOf(ProductBlock.class),
            EnumSet.allOf(PolicyAttributeScopeCode.class));

    private final Set<ProductField> fields;
    private final Set<ProductBlock> blocks;
    private final Set<PolicyAttributeScopeCode> attributeScopes;

    ProductProjection(
            Set<ProductField> fields, Set<ProductBlock> blocks, Set<PolicyAttributeScopeCode> attributeScopes) {
        this.fields = fields;
        this.blocks = blocks;
        this.attributeScopes = attributeScopes;
    }

    /** Whether this endpoint returns {@code field} at all, before policy has its say. */
    public boolean includes(ProductField field) {
        return fields.contains(field);
    }

    /** Whether this endpoint returns {@code block} at all, before policy has its say. */
    public boolean includes(ProductBlock block) {
        return blocks.contains(block);
    }

    /**
     * Whether this endpoint returns the policy attributes of one scope. A block can be returned
     * without them - a search shows which organisation offers a product without loading that
     * organisation's attributes - so this is asked separately, and answering no means the attribute
     * query for that scope is never run.
     */
    public boolean includesAttributesOf(PolicyAttributeScopeCode scope) {
        return attributeScopes.contains(scope);
    }
}
