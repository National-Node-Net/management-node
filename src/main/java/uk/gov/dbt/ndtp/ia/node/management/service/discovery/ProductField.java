/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;

/**
 * Every product field discovery knows about, and the only place a SQL column name comes from.
 * Callers and policies refer to a field by its {@link #apiName()}; they never supply SQL.
 *
 * <p>The table aliases are those of {@link ProductSearchQueryBuilder}: {@code p} product,
 * {@code pt} product type, {@code pr} producer, {@code o} owning organisation.
 *
 * <p>To expose a new column: add a constant here, add the member to {@code DiscoveredProductDTO}
 * (and map it in {@code DiscoveredProductAssembler}), and list its name in the policy's
 * {@code visible_fields} - a field policy does not list is never selected.
 */
public enum ProductField {
    /**
     * The product's identifier. Filterable so that reading one product is an ordinary search
     * constrained to it - {@code GET /api/v1/product/{productId}} compiles to {@code id eq n} - and
     * never selected here, because the page query always selects {@code p.id} in its own right.
     *
     * <p>No policy lists it in {@code allowed_filtered_fields}, so a caller cannot filter on it
     * while policy is enforced; the constraint is service-origin.
     */
    ID("id", "p.id", null, Use.FILTER_ONLY, Kind.NUMBER),
    NAME("name", "p.name", null, Use.FILTER_SORT_TEXT, Kind.TEXT),
    DESCRIPTION("description", "p.description", null, Use.FILTER_TEXT, Kind.TEXT),
    TOPIC("topic", "p.topic", null, Use.FILTER_SORT, Kind.TEXT),
    TYPE("type", "pt.name", null, Use.FILTER_SORT, Kind.TEXT),
    SOURCE("source", "p.source", null, Use.FILTER_SORT, Kind.TEXT),
    ORGANISATION_KEY("organisation.key", "o.organisation_key", ProductBlock.ORGANISATION, Use.FILTER_SORT, Kind.TEXT),
    ORGANISATION_NAME("organisation.name", "o.name", ProductBlock.ORGANISATION, Use.FILTER_SORT, Kind.TEXT),
    PRODUCER_NAME("producer.name", "pr.name", ProductBlock.PRODUCER, Use.RETURN_ONLY, Kind.TEXT),
    PRODUCER_DESCRIPTION("producer.description", "pr.description", ProductBlock.PRODUCER, Use.RETURN_ONLY, Kind.TEXT),
    PRODUCER_ACTIVE("producer.active", "pr.active", ProductBlock.PRODUCER, Use.RETURN_ONLY, Kind.TEXT),
    /**
     * The keys of the organisations holding a grant on the product. It has no column: a filter on
     * it becomes a sub-query over the grants, and it is returned as {@link ProductBlock#SUBSCRIBED_BY}.
     */
    SUBSCRIBED_BY("subscribedBy", null, ProductBlock.SUBSCRIBED_BY, Use.FILTER_ONLY, Kind.TEXT);

    /**
     * How a field's column is compared. Only consulted for a field that can be filtered: text is
     * compared case-insensitively, a number is compared as a number - {@code LOWER()} on a numeric
     * column is not a thing PostgreSQL will do.
     */
    public enum Kind {
        TEXT,
        NUMBER
    }

    /** What a field may be used for, beyond being returned. */
    private enum Use {
        RETURN_ONLY(false, false, false, true),
        FILTER_ONLY(true, false, false, false),
        FILTER_SORT(true, true, false, true),
        FILTER_TEXT(true, false, true, true),
        FILTER_SORT_TEXT(true, true, true, true);

        private final boolean filter;
        private final boolean sort;
        private final boolean text;
        private final boolean selected;

        Use(boolean filter, boolean sort, boolean text, boolean selected) {
            this.filter = filter;
            this.sort = sort;
            this.text = text;
            this.selected = selected;
        }
    }

    private final String apiName;
    private final String column;
    private final ProductBlock block;
    private final Use use;
    private final Kind kind;

    ProductField(String apiName, String column, ProductBlock block, Use use, Kind kind) {
        this.apiName = apiName;
        this.column = column;
        this.block = block;
        this.use = use;
        this.kind = kind;
    }

    /** How this field's column is compared; see {@link Kind}. */
    public Kind kind() {
        return kind;
    }

    /** The name callers and policies use, qualified when the field is not the product's own. */
    public String apiName() {
        return apiName;
    }

    /** The SQL expression the field is read from; null for {@link #SUBSCRIBED_BY}. */
    public String column() {
        return column;
    }

    /** The block this field is shown or withheld with; null for the product's own fields. */
    public ProductBlock block() {
        return block;
    }

    /** The name visibility is decided under: the block's for a block member, else the field's own. */
    public String visibilityName() {
        return block == null ? apiName : block.apiName();
    }

    /** The column alias the field is selected under. */
    public String alias() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean isFilterable() {
        return use.filter;
    }

    public boolean isSortable() {
        return use.sort;
    }

    public boolean isTextSearchable() {
        return use.text;
    }

    /** Whether the field is a column of the page query (as opposed to a filter-only field). */
    public boolean isSelected() {
        return use.selected;
    }

    /**
     * The field a caller or a policy means by {@code name} within {@code scope}.
     *
     * @param scope the entity the name belongs to
     * @param name the bare name, e.g. {@code key} within the organisation scope
     */
    public static Optional<ProductField> find(FilterScope scope, String name) {
        return findByApiName(scope.qualify(name));
    }

    public static Optional<ProductField> findByApiName(String apiName) {
        return Arrays.stream(values())
                .filter(field -> field.apiName.equals(apiName))
                .findFirst();
    }
}
