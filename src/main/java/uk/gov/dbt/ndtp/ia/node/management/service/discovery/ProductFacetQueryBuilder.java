/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterTarget;

/**
 * Builds the catalogue's filter panel in one statement: for every {@link FilterOptionTarget}
 * offered, how many of the caller's narrowed products hold each of its values - disjunctive
 * faceting, so a group's own filter never narrows its own counts.
 *
 * <p>One branch per target, unioned into a single {@link FacetQuery}:
 *
 * <pre>
 * SELECT CAST(:facetKey AS TEXT) AS facet, CAST([value expr] AS TEXT) AS value,
 *        COUNT(DISTINCT p.id) FILTER (WHERE [narrowed]) AS matching
 * [fromClause] [extra joins]
 * WHERE ([row filter])
 * GROUP BY [value expr]
 * </pre>
 *
 * <p>Every branch applies the same row filter - compiled once from {@link
 * ProductSearchContract#rowFilter()} and reused as the same SQL text in every branch - so no count
 * can cover a product policy withholds. A branch's {@code matching} count is narrowed by every
 * criteria filter <em>except</em> its own target's, plus the free text: that is what makes the
 * counts disjunctive, so a caller sees how many products would match if they changed that one
 * group, not how many match with the group's own choice already applied. Each criteria filter and
 * the text condition are themselves compiled once and their SQL text reused across branches, so
 * nothing is bound twice.
 */
@Component
public class ProductFacetQueryBuilder {

    private final String schemaPrefix;

    public ProductFacetQueryBuilder(DiscoverySchema schema) {
        this.schemaPrefix = schema.prefix();
    }

    /**
     * @param targets the filter groups to count, each already permitted by the contract; must be
     *     non-empty and every one must {@linkplain FilterOptionTarget#listsValues() list values}
     * @param callerOrganisation the calling organisation's key, for a target on {@link
     *     ProductField#IS_OWN_PRODUCT}; null or blank when it is not known
     * @throws IllegalArgumentException when {@code targets} is empty, or any of them does not list
     *     values
     * @throws FilterCompilationException when the row filter or a criteria filter cannot be
     *     translated
     */
    public FacetQuery build(
            ProductSearchContract contract,
            ProductSearchCriteria criteria,
            List<FilterOptionTarget> targets,
            String callerOrganisation) {
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("Facet counts need at least one target");
        }
        for (FilterOptionTarget target : targets) {
            if (!target.listsValues()) {
                throw new IllegalArgumentException(
                        "'" + target.key() + "' does not list values, so it cannot be faceted");
            }
        }

        SqlPredicateCompiler compiler = new SqlPredicateCompiler(schemaPrefix, callerOrganisation);
        SqlParameters parameters = new SqlParameters();

        String rowFilter = compiler.compile(contract.rowFilter(), parameters);
        String textCondition = ProductSearchQueryBuilder.textCondition(contract, criteria, parameters);
        List<FilterNode.Comparison> filters = criteria.filters();
        List<String> compiledFilters = new ArrayList<>(filters.size());
        for (FilterNode.Comparison filter : filters) {
            compiledFilters.add(compiler.compile(filter, parameters));
        }
        String from = ProductSearchQueryBuilder.fromClause(schemaPrefix);

        List<String> branches = new ArrayList<>(targets.size());
        for (FilterOptionTarget target : targets) {
            branches.add(
                    branch(target, filters, compiledFilters, textCondition, rowFilter, from, compiler, parameters));
        }
        return new FacetQuery(String.join(" UNION ALL ", branches), parameters.asMap());
    }

    private String branch(
            FilterOptionTarget target,
            List<FilterNode.Comparison> filters,
            List<String> compiledFilters,
            String textCondition,
            String rowFilter,
            String from,
            SqlPredicateCompiler compiler,
            SqlParameters parameters) {
        String facetKey = parameters.bind(target.key());
        ValueExpression value = valueExpression(target, compiler, parameters);
        String narrowed = narrowedCondition(target, filters, compiledFilters, textCondition);
        return "SELECT CAST(" + facetKey + " AS TEXT) AS facet, CAST(" + value.expression() + " AS TEXT) AS value,"
                + " COUNT(DISTINCT p.id) FILTER (WHERE " + narrowed + ") AS matching"
                + from
                + value.extraJoin()
                + " WHERE (" + rowFilter + ")"
                + " GROUP BY " + value.expression();
    }

    /**
     * Every criteria filter but this target's own, AND-ed ({@code 1 = 1} when there is none), AND-ed
     * in turn with the free text condition - the condition a target's own counts must ignore, so a
     * group's own choice never narrows its own counts.
     */
    private static String narrowedCondition(
            FilterOptionTarget target,
            List<FilterNode.Comparison> filters,
            List<String> compiledFilters,
            String textCondition) {
        List<String> others = new ArrayList<>();
        for (int i = 0; i < filters.size(); i++) {
            if (!filters.get(i).target().equals(target.target())) {
                others.add(compiledFilters.get(i));
            }
        }
        String otherFilters = others.isEmpty() ? SqlPredicateCompiler.TRUE : String.join(" AND ", others);
        return "(" + otherFilters + ") AND (" + textCondition + ")";
    }

    /** What a branch groups by, and what (if anything) it must join onto the {@code FROM} to read it. */
    private record ValueExpression(String expression, String extraJoin) {
        private static ValueExpression of(String expression) {
            return new ValueExpression(expression, "");
        }
    }

    private ValueExpression valueExpression(
            FilterOptionTarget target, SqlPredicateCompiler compiler, SqlParameters parameters) {
        ProductField field = target.field();
        if (field == null) {
            return attributeValueExpression(target.target(), parameters);
        }
        return switch (field) {
            case SUBSCRIBED_BY ->
                new ValueExpression(
                        "fo.organisation_key",
                        " LEFT JOIN " + schemaPrefix + "product_consumer fpc ON fpc.product_id = p.id"
                                + " LEFT JOIN " + schemaPrefix + "consumer fc ON fc.id = fpc.consumer_id"
                                + " LEFT JOIN " + schemaPrefix + "organisation fo ON fo.id = fc.org_id");
            case IS_OWN_PRODUCT ->
                ValueExpression.of(
                        "CASE WHEN " + compiler.ownProductCondition(parameters) + " THEN 'true' ELSE 'false' END");
            default -> ValueExpression.of(field.column());
        };
    }

    private ValueExpression attributeValueExpression(FilterTarget target, SqlParameters parameters) {
        FilterScope scope = target.scope();
        String scopeParameter = parameters.bind(scope.attributeScopeCode());
        String nameParameter = parameters.bind(target.name());
        String join = " LEFT JOIN " + schemaPrefix + "policy_attribute_live_value fa ON fa.entity_id = "
                + SqlPredicateCompiler.ownerId(scope) + " AND fa.scope_code = " + scopeParameter
                + " AND fa.name = " + nameParameter;
        return new ValueExpression("fa.item", join);
    }
}
