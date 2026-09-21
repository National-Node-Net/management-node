/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductPolicyContractDetails.UnmaskRule;

/**
 * Builds the SQL of a discovery search from the contract (what policy decided) and the criteria
 * (what the caller asked for). Both halves of the statement come from the contract:
 *
 * <pre>
 * SELECT p.id, [each field the caller may see], [one flag per unmask rule]
 * FROM product p JOIN producer pr ... JOIN organisation o ... LEFT JOIN product_type pt ...
 * WHERE ([policy row filter]) AND ([caller filters]) AND ([free text])
 * ORDER BY [sort keys], p.id  LIMIT ... OFFSET ...
 * </pre>
 *
 * <p>A field the caller may not see is not in the {@code SELECT} list, so its value never leaves
 * the database. A field an unmask rule shows on some products is selected through
 * {@code CASE WHEN [rule's condition] THEN column END}, so it is read only for those products.
 *
 * <p>The policy's condition and the caller's are compiled separately and joined with {@code AND},
 * so nothing a caller sends can loosen what policy requires.
 */
@Component
public class ProductSearchQueryBuilder {

    private final String schemaPrefix;
    private final SqlPredicateCompiler compiler;

    public ProductSearchQueryBuilder(DiscoverySchema schema) {
        this.schemaPrefix = schema.prefix();
        this.compiler = new SqlPredicateCompiler(schemaPrefix);
    }

    /**
     * @param projection how much of a product this endpoint returns; it can only narrow what the
     *     contract already permits
     * @throws FilterCompilationException when the contract's row filter or an unmask condition
     *     cannot be translated; the caller's criteria were validated when they were created
     */
    public ProductSearchQuery build(
            ProductSearchContract contract, ProductSearchCriteria criteria, ProductProjection projection) {
        SqlParameters parameters = new SqlParameters();
        List<String> unmaskConditions = contract.unmaskRules().stream()
                .map(rule -> compiler.compile(rule.when(), parameters))
                .toList();

        String from = " FROM " + schemaPrefix + "product p"
                + " JOIN " + schemaPrefix + "producer pr ON pr.id = p.producer_id"
                + " JOIN " + schemaPrefix + "organisation o ON o.id = pr.org_id"
                + " LEFT JOIN " + schemaPrefix + "product_type pt ON pt.id = p.product_type_id";
        String where = " WHERE (" + compiler.compile(contract.rowFilter(), parameters) + ")"
                + " AND (" + compiler.compile(FilterNode.Group.and(criteria.filters()), parameters) + ")"
                + " AND (" + textCondition(contract, criteria, parameters) + ")";

        String pageSql = "SELECT " + selectList(contract, projection, unmaskConditions) + from + where
                + " ORDER BY " + orderBy(criteria, parameters)
                + " LIMIT " + parameters.bind(criteria.size())
                + " OFFSET " + parameters.bind(criteria.offset());
        String countSql = "SELECT COUNT(*)" + from + where;
        return new ProductSearchQuery(pageSql, countSql, parameters.asMap());
    }

    private static String selectList(
            ProductSearchContract contract, ProductProjection projection, List<String> unmaskConditions) {
        List<String> columns = new ArrayList<>();
        columns.add("p.id AS " + ProductSearchQuery.ID);
        columns.add("o.id AS " + ProductSearchQuery.ORGANISATION_ID);
        columns.add("pr.id AS " + ProductSearchQuery.PRODUCER_ID);

        for (ProductField field : ProductField.values()) {
            // A field this endpoint does not return is not selected, so it is never read - the
            // projection narrows exactly the way masking does, and for the same reason.
            if (!field.isSelected() || !projection.includes(field)) {
                continue;
            }
            if (isShownOnEveryProduct(contract, field)) {
                columns.add(field.column() + " AS " + field.alias());
                continue;
            }
            // Withheld in general - but a rule may still show it on the products matching a
            // condition, and then the column is read only for those.
            String shownWhen = conditionsUnmasking(field, contract.unmaskRules(), unmaskConditions);
            if (!shownWhen.isEmpty()) {
                columns.add("CASE WHEN " + shownWhen + " THEN " + field.column() + " END AS " + field.alias());
            }
        }
        for (int i = 0; i < unmaskConditions.size(); i++) {
            columns.add("CASE WHEN " + unmaskConditions.get(i) + " THEN TRUE ELSE FALSE END AS "
                    + ProductSearchQuery.unmaskColumn(i));
        }
        return String.join(", ", columns);
    }

    /**
     * Whether a field is shown on every product: its block must be visible, and the field must not
     * be masked in its own right ({@code organisation} visible, {@code organisation.name} masked).
     */
    private static boolean isShownOnEveryProduct(ProductSearchContract contract, ProductField field) {
        return contract.isVisible(field.visibilityName()) && !contract.isMasked(field.apiName());
    }

    /**
     * The conditions of the rules that show {@code field}, OR-ed; empty when no rule does. A rule
     * may name the field itself ({@code organisation.name}) or the block it belongs to
     * ({@code organisation}), and either shows it.
     */
    private static String conditionsUnmasking(ProductField field, List<UnmaskRule> rules, List<String> conditions) {
        List<String> matching = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            List<String> names = rules.get(i).names();
            if (names.contains(field.apiName()) || names.contains(field.visibilityName())) {
                matching.add("(" + conditions.get(i) + ")");
            }
        }
        return String.join(" OR ", matching);
    }

    private static String textCondition(
            ProductSearchContract contract, ProductSearchCriteria criteria, SqlParameters parameters) {
        if (!criteria.hasText()) {
            return SqlPredicateCompiler.TRUE;
        }
        List<ProductField> fields = contract.textSearchFields();
        if (fields.isEmpty()) {
            return SqlPredicateCompiler.FALSE;
        }
        String pattern = parameters.bind(SqlPredicateCompiler.containsPattern(criteria.text()));
        return fields.stream()
                .map(field -> "LOWER(" + field.column() + ") LIKE " + pattern + SqlPredicateCompiler.likeEscape())
                .collect(Collectors.joining(" OR "));
    }

    private String orderBy(ProductSearchCriteria criteria, SqlParameters parameters) {
        List<String> keys = new ArrayList<>();
        for (ProductSearchCriteria.SortKey key : criteria.sort()) {
            String expression = key.field() != null
                    ? "LOWER(" + key.field().column() + ")"
                    : "(SELECT MIN(a.item) FROM " + schemaPrefix + "policy_attribute_live_value a"
                            + " WHERE a.entity_id = " + SqlPredicateCompiler.ownerId(key.scope())
                            + " AND a.scope_code = "
                            + parameters.bind(key.scope().attributeScopeCode())
                            + " AND a.name = " + parameters.bind(key.attribute()) + ")";
            keys.add(expression + (key.descending() ? " DESC" : " ASC") + " NULLS LAST");
        }
        keys.add("p.id ASC");
        return String.join(", ", keys);
    }
}
