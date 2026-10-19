/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.Combinator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.ComparisonOperator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;

/**
 * Translates a {@link FilterNode} tree into a SQL condition over the product query of
 * {@link ProductSearchQueryBuilder}.
 *
 * <p>Two kinds of target compile differently:
 *
 * <ul>
 *   <li>a <b>field</b> is a column, taken from {@link ProductField} - {@code LOWER(p.name) = :p0};
 *   <li>an <b>attribute</b> is stored as rows, so it becomes a sub-query over the
 *       {@code policy_attribute_live_value} view - {@code EXISTS (... a.name = :p0 AND a.item IN (:p1))}.
 *       The attribute <em>name</em> is a bound value like any other, which is why a new attribute
 *       needs no code change.
 * </ul>
 *
 * <p>Rules that keep this safe, none of which may be relaxed:
 *
 * <ul>
 *   <li>column names come only from {@link ProductField}; every value is bound through
 *       {@link SqlParameters};
 *   <li>a product without the attribute fails every operator except the negative ones
 *       ({@link ComparisonOperator#isNegative()});
 *   <li>an empty list matches nothing ({@code in []} is false, {@code none_of []} is true);
 *   <li>anything that cannot be translated throws rather than being skipped.
 * </ul>
 */
public class SqlPredicateCompiler {

    static final String TRUE = "1 = 1";
    static final String FALSE = "1 = 0";
    private static final String AND = " AND ";
    private static final String LIKE_ESCAPE = " ESCAPE '\\'";

    private final String schemaPrefix;
    private final String callerOrganisation;

    /**
     * A compiler for a search whose caller's organisation is not known, so no product is the
     * caller's own.
     *
     * @param schemaPrefix what to put before a table name, e.g. {@code "mn".} - or an empty string
     */
    public SqlPredicateCompiler(String schemaPrefix) {
        this(schemaPrefix, null);
    }

    /**
     * @param schemaPrefix what to put before a table name, e.g. {@code "mn".} - or an empty string
     * @param callerOrganisation the calling organisation's key, which {@link ProductField#IS_OWN_PRODUCT}
     *     is compared against; null or blank when it is not known
     */
    public SqlPredicateCompiler(String schemaPrefix, String callerOrganisation) {
        this.schemaPrefix = schemaPrefix;
        this.callerOrganisation =
                callerOrganisation == null || callerOrganisation.isBlank() ? null : callerOrganisation;
    }

    /**
     * @param node the predicate to translate
     * @param parameters where the predicate's values are bound
     * @return a SQL condition, safe to wrap in parentheses and combine with others
     * @throws FilterCompilationException when the predicate cannot be translated
     */
    public String compile(FilterNode node, SqlParameters parameters) {
        return switch (node) {
            case null -> throw new FilterCompilationException("The filter contains an empty node");
            case FilterNode.Literal(boolean value) -> value ? TRUE : FALSE;
            case FilterNode.Group group -> group(group, parameters);
            case FilterNode.Comparison comparison -> comparison(comparison, parameters);
        };
    }

    private String group(FilterNode.Group group, SqlParameters parameters) {
        boolean and = group.combinator() == Combinator.AND;
        if (group.nodes().isEmpty()) {
            return and ? TRUE : FALSE;
        }
        return group.nodes().stream()
                .map(node -> compile(node, parameters))
                .collect(Collectors.joining(and ? AND : " OR ", "(", ")"));
    }

    private String comparison(FilterNode.Comparison comparison, SqlParameters parameters) {
        ComparisonOperator operator = comparison.operator();
        if (!operator.accepts(comparison.values().size())) {
            throw new FilterCompilationException("Operator '" + operator.wireName() + "' on '"
                    + comparison.target().qualifiedName() + "' was given "
                    + comparison.values().size() + " value(s)");
        }
        if (!comparison.target().field()) {
            return attribute(comparison, parameters);
        }
        ProductField field = ProductField.find(
                        comparison.target().scope(), comparison.target().name())
                .filter(ProductField::isFilterable)
                .orElseThrow(() -> new FilterCompilationException(
                        "'" + comparison.target().qualifiedName() + "' is not a field products can be filtered on"));
        return switch (field) {
            case SUBSCRIBED_BY -> subscribedBy(comparison, parameters);
            case IS_OWN_PRODUCT -> ownProduct(comparison, parameters);
            default -> column(field, comparison, parameters);
        };
    }

    /**
     * Whether the calling organisation offers the current row's product, as a SQL condition. Keys
     * are compared case-insensitively, as every other organisation key is. A caller whose
     * organisation is not known offers nothing.
     */
    String ownProductCondition(SqlParameters parameters) {
        return callerOrganisation == null
                ? FALSE
                : "LOWER(o.organisation_key) = " + parameters.bind(callerOrganisation.toLowerCase(Locale.ROOT));
    }

    /** A filter on whether the caller offers the product: which of {@code true} and {@code false} match. */
    private String ownProduct(FilterNode.Comparison comparison, SqlParameters parameters) {
        return switch (comparison.operator()) {
            case EQ, IN, ANY_OF -> ownProductIn(booleans(comparison), parameters);
            case NEQ, NOT_IN, NONE_OF -> ownProductIn(complement(booleans(comparison)), parameters);
            // Every product either is or is not the caller's own; the value is never missing.
            case EXISTS -> TRUE;
            case NOT_EXISTS -> FALSE;
            default -> throw notApplicable(comparison);
        };
    }

    private String ownProductIn(Set<Boolean> values, SqlParameters parameters) {
        if (values.size() == 2) {
            return TRUE;
        }
        if (values.isEmpty()) {
            return FALSE;
        }
        String own = ownProductCondition(parameters);
        return values.contains(Boolean.TRUE) ? own : "NOT (" + own + ")";
    }

    private static Set<Boolean> complement(Set<Boolean> values) {
        Set<Boolean> rest = new LinkedHashSet<>(Set.of(Boolean.TRUE, Boolean.FALSE));
        rest.removeAll(values);
        return rest;
    }

    private static Set<Boolean> booleans(FilterNode.Comparison comparison) {
        Set<Boolean> values = new LinkedHashSet<>();
        for (Object value : comparison.values()) {
            Boolean parsed = booleanValue(value)
                    .orElseThrow(() -> new FilterCompilationException(
                            "'" + comparison.target().qualifiedName() + "' needs true or false, not '" + value + "'"));
            values.add(parsed);
        }
        return values;
    }

    /**
     * {@code value} as a boolean - a JSON boolean, or the text {@code true} or {@code false} in any
     * case - or empty when it is neither.
     */
    static Optional<Boolean> booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return Optional.of(bool);
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) {
                return Optional.of(Boolean.TRUE);
            }
            if ("false".equalsIgnoreCase(text)) {
                return Optional.of(Boolean.FALSE);
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------------------------------
    // Fields: string columns, compared case-insensitively
    // ---------------------------------------------------------------------------------------

    private String column(ProductField field, FilterNode.Comparison comparison, SqlParameters parameters) {
        return field.kind() == ProductField.Kind.NUMBER
                ? numericColumn(field, comparison, parameters)
                : textColumn(field, comparison, parameters);
    }

    /** A text column, compared case-insensitively - which is how a caller expects names to match. */
    private String textColumn(ProductField field, FilterNode.Comparison comparison, SqlParameters parameters) {
        String column = field.column();
        String lowered = "LOWER(" + column + ")";
        Set<String> values = lowerCased(comparison.values());
        return switch (comparison.operator()) {
            case EQ, IN, ANY_OF -> values.isEmpty() ? FALSE : lowered + " IN (" + parameters.bind(values) + ")";
            case NEQ, NOT_IN, NONE_OF ->
                values.isEmpty()
                        ? TRUE
                        : "(" + column + " IS NULL OR " + lowered + " NOT IN (" + parameters.bind(values) + "))";
            case CONTAINS -> lowered + " LIKE " + parameters.bind(containsPattern(comparison)) + LIKE_ESCAPE;
            case EXISTS -> column + " IS NOT NULL";
            case NOT_EXISTS -> column + " IS NULL";
            default -> throw notApplicable(comparison);
        };
    }

    /**
     * A numeric column, compared as a number. Case folding is not merely pointless here but an
     * error - PostgreSQL has no {@code LOWER(bigint)} - so the operands are converted rather than
     * lower-cased, and one that is not a number fails the compilation instead of the query.
     */
    private String numericColumn(ProductField field, FilterNode.Comparison comparison, SqlParameters parameters) {
        String column = field.column();
        return switch (comparison.operator()) {
            case EQ, IN, ANY_OF -> {
                Set<BigDecimal> values = numbers(comparison);
                yield values.isEmpty() ? FALSE : column + " IN (" + parameters.bind(values) + ")";
            }
            case NEQ, NOT_IN, NONE_OF -> {
                Set<BigDecimal> values = numbers(comparison);
                yield values.isEmpty()
                        ? TRUE
                        : "(" + column + " IS NULL OR " + column + " NOT IN (" + parameters.bind(values) + "))";
            }
            case LT, LTE, GT, GTE ->
                column + " " + ordering(comparison.operator()) + " " + parameters.bind(number(comparison));
            case EXISTS -> column + " IS NOT NULL";
            case NOT_EXISTS -> column + " IS NULL";
            default -> throw notApplicable(comparison);
        };
    }

    /** Every operand as a number, in the order given; anything that is not one fails the search. */
    private static Set<BigDecimal> numbers(FilterNode.Comparison comparison) {
        return comparison.values().stream()
                .map(value -> number(comparison, value))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** A filter on the organisations using a product: a sub-query over its grants. */
    private String subscribedBy(FilterNode.Comparison comparison, SqlParameters parameters) {
        String grants = "SELECT 1 FROM " + schemaPrefix + "product_consumer pc"
                + " JOIN " + schemaPrefix + "consumer c ON c.id = pc.consumer_id"
                + " JOIN " + schemaPrefix + "organisation co ON co.id = c.org_id"
                + " WHERE pc.product_id = p.id";
        Set<String> keys = lowerCased(comparison.values());
        return switch (comparison.operator()) {
            case EQ, IN, ANY_OF -> keys.isEmpty() ? FALSE : "EXISTS (" + grants + keyIn(keys, parameters) + ")";
            case NEQ, NOT_IN, NONE_OF ->
                keys.isEmpty() ? TRUE : "NOT EXISTS (" + grants + keyIn(keys, parameters) + ")";
            case EXISTS -> "EXISTS (" + grants + ")";
            case NOT_EXISTS -> "NOT EXISTS (" + grants + ")";
            default -> throw notApplicable(comparison);
        };
    }

    private static String keyIn(Set<String> keys, SqlParameters parameters) {
        return " AND LOWER(co.organisation_key) IN (" + parameters.bind(keys) + ")";
    }

    // ---------------------------------------------------------------------------------------
    // Attributes: rows of the policy_attribute_live_value view
    // ---------------------------------------------------------------------------------------

    private String attribute(FilterNode.Comparison comparison, SqlParameters parameters) {
        String rows =
                attributeRows(comparison.target().scope(), comparison.target().name(), parameters);
        Set<String> values = asText(comparison.values());
        return switch (comparison.operator()) {
            case EQ, IN, ANY_OF -> values.isEmpty() ? FALSE : exists(rows, itemIn(values, parameters));
            case NEQ, NOT_IN, NONE_OF -> values.isEmpty() ? TRUE : "NOT " + exists(rows, itemIn(values, parameters));
            case ALL_OF ->
                values.isEmpty()
                        ? TRUE
                        : "(SELECT COUNT(DISTINCT a.item)" + rows + AND + itemIn(values, parameters) + ") = "
                                + parameters.bind(values.size());
            case CONTAINS ->
                exists(rows, "LOWER(a.item) LIKE " + parameters.bind(containsPattern(comparison)) + LIKE_ESCAPE);
            case LT, LTE, GT, GTE ->
                // CASE rather than AND: SQL may evaluate AND's operands in any order, and the cast
                // must never run on a value that is not a number.
                exists(
                        rows,
                        "CASE WHEN a.item_type = 'number' THEN CAST(a.item AS numeric) END "
                                + ordering(comparison.operator()) + " " + parameters.bind(number(comparison)));
            case EXISTS -> exists(rows, TRUE);
            case NOT_EXISTS -> "NOT " + exists(rows, TRUE);
        };
    }

    /** {@code FROM ... WHERE ...} selecting the live values of one attribute of the current row. */
    private String attributeRows(FilterScope scope, String attributeName, SqlParameters parameters) {
        return " FROM " + schemaPrefix + "policy_attribute_live_value a"
                + " WHERE a.entity_id = " + ownerId(scope)
                + " AND a.scope_code = " + parameters.bind(scope.attributeScopeCode())
                + " AND a.name = " + parameters.bind(attributeName);
    }

    /** The id column, in the product query, of the entity a scope's attributes are stored against. */
    static String ownerId(FilterScope scope) {
        return scope == FilterScope.ORGANISATION ? "o.id" : "p.id";
    }

    private static String exists(String rows, String condition) {
        return "EXISTS (SELECT 1" + rows + AND + condition + ")";
    }

    private static String itemIn(Set<String> values, SqlParameters parameters) {
        return "a.item IN (" + parameters.bind(values) + ")";
    }

    private static String ordering(ComparisonOperator operator) {
        return switch (operator) {
            case LT -> "<";
            case LTE -> "<=";
            case GT -> ">";
            case GTE -> ">=";
            default -> throw new IllegalArgumentException(operator + " is not an ordering operator");
        };
    }

    // ---------------------------------------------------------------------------------------
    // Operand values
    // ---------------------------------------------------------------------------------------

    private static Set<String> asText(List<Object> values) {
        return values.stream().map(String::valueOf).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> lowerCased(List<Object> values) {
        return values.stream()
                .map(value -> String.valueOf(value).toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static BigDecimal number(FilterNode.Comparison comparison) {
        return number(comparison, comparison.values().get(0));
    }

    private static BigDecimal number(FilterNode.Comparison comparison, Object value) {
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new FilterCompilationException(
                    "Operator '" + comparison.operator().wireName() + "' on '"
                            + comparison.target().qualifiedName() + "' needs a number, not '" + value + "'");
        }
    }

    private static String containsPattern(FilterNode.Comparison comparison) {
        return containsPattern(String.valueOf(comparison.values().get(0)));
    }

    /**
     * A lower-cased {@code %value%} pattern in which the LIKE wildcards of {@code value} itself are
     * escaped, so they are matched literally. Goes with {@code ESCAPE '\'}.
     */
    static String containsPattern(String value) {
        String escaped = value.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    static String likeEscape() {
        return LIKE_ESCAPE;
    }

    private static FilterCompilationException notApplicable(FilterNode.Comparison comparison) {
        return new FilterCompilationException(
                "Operator '" + comparison.operator().wireName() + "' does not apply to the field '"
                        + comparison.target().qualifiedName() + "'");
    }
}
