/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.Combinator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.ComparisonOperator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterTarget;

/**
 * Covers the translation of a filter tree to SQL: the exact text of every operator on a field and
 * on an attribute, and the rules that keep it safe - identifiers come only from the field registry,
 * every value (an attribute's <em>name</em> included) is bound, an empty list matches nothing, and
 * anything untranslatable throws instead of being dropped.
 */
class SqlPredicateCompilerTest {

    private final SqlPredicateCompiler compiler = new SqlPredicateCompiler("");
    private final SqlParameters parameters = new SqlParameters();

    private String compile(FilterNode node) {
        return compiler.compile(node, parameters);
    }

    private static FilterNode.Comparison field(String name, ComparisonOperator operator, Object... values) {
        return FilterNode.Comparison.ofField(name, operator, values);
    }

    private static FilterNode.Comparison attribute(String name, ComparisonOperator operator, Object... values) {
        return FilterNode.Comparison.ofAttribute(name, operator, values);
    }

    // -----------------------------------------------------------------------------------------
    // Literals and groups
    // -----------------------------------------------------------------------------------------

    @Test
    void compile_literal_isAConstantCondition() {
        assertThat(compile(FilterNode.ALLOW_ALL)).isEqualTo("1 = 1");
        assertThat(compile(FilterNode.DENY_ALL)).isEqualTo("1 = 0");
        assertThat(parameters.asMap()).isEmpty();
    }

    @Test
    void compile_emptyAndGroup_matchesEveryProduct() {
        assertThat(compile(new FilterNode.Group(Combinator.AND, List.of()))).isEqualTo("1 = 1");
    }

    @Test
    void compile_emptyOrGroup_matchesNoProduct() {
        assertThat(compile(new FilterNode.Group(Combinator.OR, List.of()))).isEqualTo("1 = 0");
    }

    @Test
    void compile_nestedGroups_parenthesisesEachGroup() {
        FilterNode node = new FilterNode.Group(
                Combinator.AND,
                List.of(
                        field("topic", ComparisonOperator.EQ, "Weather"),
                        new FilterNode.Group(
                                Combinator.OR,
                                List.of(field("source", ComparisonOperator.EQ, "sensor"), FilterNode.DENY_ALL))));

        assertThat(compile(node)).isEqualTo("(LOWER(p.topic) IN (:p0) AND (LOWER(p.source) IN (:p1) OR 1 = 0))");
        assertThat(parameters.asMap()).containsExactly(entry("p0", Set.of("weather")), entry("p1", Set.of("sensor")));
    }

    @Test
    void compile_nullNode_throws() {
        assertThatThrownBy(() -> compile(null))
                .isInstanceOf(FilterCompilationException.class)
                .hasMessageContaining("empty node");
    }

    // -----------------------------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------------------------

    @Test
    void compile_fieldEq_isALowerCasedMembershipTest() {
        assertThat(compile(field("name", ComparisonOperator.EQ, "Alpha"))).isEqualTo("LOWER(p.name) IN (:p0)");
        assertThat(parameters.asMap()).containsExactly(entry("p0", Set.of("alpha")));
    }

    @Test
    void compile_fieldIn_bindsEveryValueLowerCased() {
        assertThat(compile(field("type", ComparisonOperator.IN, "Topic", "FILE")))
                .isEqualTo("LOWER(pt.name) IN (:p0)");
        assertThat(parameters.asMap()).containsExactly(entry("p0", Set.of("topic", "file")));
    }

    @Test
    void compile_fieldAnyOf_compilesLikeIn() {
        assertThat(compile(field("source", ComparisonOperator.ANY_OF, "sensor")))
                .isEqualTo("LOWER(p.source) IN (:p0)");
    }

    /** A NULL column is not "different from x" in SQL, so the null arm has to be spelled out. */
    @Test
    void compile_fieldNeq_alsoMatchesRowsWhereTheColumnIsNull() {
        assertThat(compile(field("source", ComparisonOperator.NEQ, "Sensor")))
                .isEqualTo("(p.source IS NULL OR LOWER(p.source) NOT IN (:p0))");
        assertThat(parameters.asMap()).containsExactly(entry("p0", Set.of("sensor")));
    }

    @Test
    void compile_fieldNotInAndNoneOf_compileTheSameWay() {
        String notIn = compile(field("topic", ComparisonOperator.NOT_IN, "a"));
        String noneOf = compiler.compile(field("topic", ComparisonOperator.NONE_OF, "a"), new SqlParameters());

        assertThat(notIn).isEqualTo("(p.topic IS NULL OR LOWER(p.topic) NOT IN (:p0))");
        assertThat(noneOf).isEqualTo(notIn);
    }

    @Test
    void compile_fieldContains_isAnEscapedLikePattern() {
        assertThat(compile(field("name", ComparisonOperator.CONTAINS, "Plan")))
                .isEqualTo("LOWER(p.name) LIKE :p0 ESCAPE '\\'");
        assertThat(parameters.asMap()).containsExactly(entry("p0", "%plan%"));
    }

    @Test
    void compile_fieldExistsAndNotExists_areNullChecks() {
        assertThat(compile(field("source", ComparisonOperator.EXISTS))).isEqualTo("p.source IS NOT NULL");
        assertThat(compile(field("source", ComparisonOperator.NOT_EXISTS))).isEqualTo("p.source IS NULL");
        assertThat(parameters.asMap()).isEmpty();
    }

    @Test
    void compile_fieldWithEmptyInList_matchesNoProduct() {
        assertThat(compile(new FilterNode.Comparison(FilterTarget.ofField("topic"), ComparisonOperator.IN, List.of())))
                .isEqualTo("1 = 0");
        assertThat(parameters.asMap()).isEmpty();
    }

    @Test
    void compile_fieldWithEmptyNoneOfList_matchesEveryProduct() {
        assertThat(compile(new FilterNode.Comparison(
                        FilterTarget.ofField("topic"), ComparisonOperator.NONE_OF, List.of())))
                .isEqualTo("1 = 1");
    }

    @Test
    void compile_operatorThatDoesNotApplyToAField_throws() {
        FilterNode.Comparison comparison = field("name", ComparisonOperator.ALL_OF, "a");
        assertThatThrownBy(() -> compile(comparison))
                .isInstanceOf(FilterCompilationException.class)
                .hasMessageContaining("all_of")
                .hasMessageContaining("does not apply to the field 'name'");
    }

    @Test
    void compile_orderingOperatorOnAField_throws() {
        FilterNode.Comparison comparison = field("name", ComparisonOperator.GT, 3);
        assertThatThrownBy(() -> compile(comparison))
                .isInstanceOf(FilterCompilationException.class)
                .hasMessageContaining("does not apply to the field 'name'");
    }

    @Test
    void compile_unknownFieldName_throws() {
        FilterNode.Comparison comparison = field("secret_column", ComparisonOperator.EQ, "x");
        assertThatThrownBy(() -> compile(comparison))
                .isInstanceOf(FilterCompilationException.class)
                .hasMessageContaining("'secret_column' is not a field products can be filtered on");
    }

    /** A field that exists but is only ever returned must not become a filter. */
    @Test
    void compile_fieldThatIsNotFilterable_throws() {
        FilterNode.Comparison comparison = field("producer.name", ComparisonOperator.EQ, "x");
        assertThatThrownBy(() -> compile(comparison))
                .isInstanceOf(FilterCompilationException.class)
                .hasMessageContaining("'producer.name' is not a field products can be filtered on");
    }

    @Test
    void compile_operatorGivenTheWrongNumberOfValues_throws() {
        FilterNode.Comparison comparison = field("name", ComparisonOperator.EQ, "a", "b");
        assertThatThrownBy(() -> compile(comparison))
                .isInstanceOf(FilterCompilationException.class)
                .hasMessageContaining("was given 2 value(s)");
    }

    // -----------------------------------------------------------------------------------------
    // Numeric fields: compared as numbers, never case-folded
    // -----------------------------------------------------------------------------------------

    /**
     * The one thing a numeric column must not do. {@code LOWER(bigint)} is not a function
     * PostgreSQL has, so a numeric field that went down the text path would fail at execution -
     * after the statement had been built and the plan looked correct.
     */
    @Test
    void compile_numericFieldEq_comparesTheColumnWithoutLowerCasingIt() {
        String sql = compile(field("id", ComparisonOperator.EQ, 3L));

        assertThat(sql).isEqualTo("p.id IN (:p0)").doesNotContain("LOWER(p.id)", "LOWER(");
        assertThat(parameters.asMap()).containsExactly(entry("p0", Set.of(new BigDecimal("3"))));
    }

    @Test
    void compile_numericFieldIn_bindsEveryValueAsANumber() {
        String sql = compile(field("id", ComparisonOperator.IN, 1, 2, 3));

        assertThat(sql).isEqualTo("p.id IN (:p0)");
        assertThat(parameters.asMap())
                .containsExactly(entry("p0", Set.of(new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("3"))));
    }

    @Test
    void compile_numericFieldNeq_alsoMatchesRowsWhereTheColumnIsNullAndDoesNotLowerCase() {
        String sql = compile(field("id", ComparisonOperator.NEQ, 3));

        assertThat(sql).isEqualTo("(p.id IS NULL OR p.id NOT IN (:p0))").doesNotContain("LOWER(p.id)");
        assertThat(parameters.asMap()).containsExactly(entry("p0", Set.of(new BigDecimal("3"))));
    }

    @Test
    void compile_numericFieldNotInAndNoneOf_compileTheSameWay() {
        String notIn = compile(field("id", ComparisonOperator.NOT_IN, 1, 2));
        String noneOf = compiler.compile(field("id", ComparisonOperator.NONE_OF, 1, 2), new SqlParameters());

        assertThat(notIn).isEqualTo("(p.id IS NULL OR p.id NOT IN (:p0))");
        assertThat(noneOf).isEqualTo(notIn);
    }

    @Test
    void compile_numericFieldWithEmptyValueList_failsClosedForPositivesAndOpenForNegatives() {
        assertThat(compile(new FilterNode.Comparison(FilterTarget.ofField("id"), ComparisonOperator.IN, List.of())))
                .isEqualTo("1 = 0");
        assertThat(compile(
                        new FilterNode.Comparison(FilterTarget.ofField("id"), ComparisonOperator.NONE_OF, List.of())))
                .isEqualTo("1 = 1");
        assertThat(parameters.asMap()).isEmpty();
    }

    @Test
    void compile_numericFieldOrderingOperators_compareTheColumnAsANumber() {
        assertThat(compile(field("id", ComparisonOperator.GTE, 10))).isEqualTo("p.id >= :p0");
        assertThat(compiler.compile(field("id", ComparisonOperator.LT, 10), new SqlParameters()))
                .isEqualTo("p.id < :p0");
        assertThat(parameters.asMap()).containsExactly(entry("p0", new BigDecimal("10")));
    }

    @Test
    void compile_numericFieldExistsAndNotExists_areNullChecks() {
        assertThat(compile(field("id", ComparisonOperator.EXISTS))).isEqualTo("p.id IS NOT NULL");
        assertThat(compile(field("id", ComparisonOperator.NOT_EXISTS))).isEqualTo("p.id IS NULL");
        assertThat(parameters.asMap()).isEmpty();
    }

    /** A non-numeric operand fails the compilation, rather than being handed to the database. */
    @Test
    void compile_numericFieldWithANonNumericValue_throws() {
        FilterNode.Comparison comparison = field("id", ComparisonOperator.EQ, "abc");
        assertThatThrownBy(() -> compile(comparison))
                .isInstanceOf(FilterCompilationException.class)
                .hasMessageContaining("needs a number, not 'abc'");
    }

    @Test
    void compile_numericFieldWithOneNonNumericValueAmongNumbers_throws() {
        FilterNode.Comparison comparison = field("id", ComparisonOperator.IN, 1, "two", 3);
        assertThatThrownBy(() -> compile(comparison))
                .isInstanceOf(FilterCompilationException.class)
                .hasMessageContaining("needs a number, not 'two'");
    }

    /** {@code contains} is a substring test; it has no meaning on a number. */
    @Test
    void compile_containsOnANumericField_throws() {
        FilterNode.Comparison comparison = field("id", ComparisonOperator.CONTAINS, "3");
        assertThatThrownBy(() -> compile(comparison))
                .isInstanceOf(FilterCompilationException.class)
                .hasMessageContaining("contains")
                .hasMessageContaining("does not apply to the field 'id'");
    }

    /**
     * The regression guard for the split: introducing the numeric path must leave every text field
     * compiling exactly as it did, {@code LOWER(...)} and all.
     */
    @Test
    void compile_textFields_stillCompileWithLowerCasing() {
        assertThat(compile(field("name", ComparisonOperator.EQ, "Alpha"))).isEqualTo("LOWER(p.name) IN (:p0)");
        assertThat(compiler.compile(field("topic", ComparisonOperator.NEQ, "Weather"), new SqlParameters()))
                .isEqualTo("(p.topic IS NULL OR LOWER(p.topic) NOT IN (:p0))");
        assertThat(compiler.compile(field("type", ComparisonOperator.IN, "Topic", "FILE"), new SqlParameters()))
                .isEqualTo("LOWER(pt.name) IN (:p0)");
        assertThat(compiler.compile(field("name", ComparisonOperator.CONTAINS, "Plan"), new SqlParameters()))
                .isEqualTo("LOWER(p.name) LIKE :p0 ESCAPE '\\'");
        assertThat(compiler.compile(field("source", ComparisonOperator.EXISTS), new SqlParameters()))
                .isEqualTo("p.source IS NOT NULL");
    }

    // -----------------------------------------------------------------------------------------
    // subscribedBy: a sub-query over the grants
    // -----------------------------------------------------------------------------------------

    @Test
    void compile_subscribedByAnyOf_isAnExistsOverTheGrants() {
        SqlPredicateCompiler schemaCompiler = new SqlPredicateCompiler(new DiscoverySchema("mn").prefix());

        String sql = schemaCompiler.compile(field("subscribedBy", ComparisonOperator.ANY_OF, "ENV", "BCC"), parameters);

        assertThat(sql)
                .isEqualTo("EXISTS (SELECT 1 FROM \"mn\".product_consumer pc"
                        + " JOIN \"mn\".consumer c ON c.id = pc.consumer_id"
                        + " JOIN \"mn\".organisation co ON co.id = c.org_id"
                        + " WHERE pc.product_id = p.id AND LOWER(co.organisation_key) IN (:p0))");
        assertThat(parameters.asMap()).containsExactly(entry("p0", Set.of("env", "bcc")));
    }

    @Test
    void compile_subscribedByNoneOf_isANotExistsOverTheGrants() {
        assertThat(compile(field("subscribedBy", ComparisonOperator.NONE_OF, "ENV")))
                .isEqualTo("NOT EXISTS (SELECT 1 FROM product_consumer pc"
                        + " JOIN consumer c ON c.id = pc.consumer_id"
                        + " JOIN organisation co ON co.id = c.org_id"
                        + " WHERE pc.product_id = p.id AND LOWER(co.organisation_key) IN (:p0))");
    }

    @Test
    void compile_subscribedByExists_asksOnlyWhetherAGrantExists() {
        assertThat(compile(field("subscribedBy", ComparisonOperator.EXISTS)))
                .isEqualTo("EXISTS (SELECT 1 FROM product_consumer pc"
                        + " JOIN consumer c ON c.id = pc.consumer_id"
                        + " JOIN organisation co ON co.id = c.org_id"
                        + " WHERE pc.product_id = p.id)");
        assertThat(compile(field("subscribedBy", ComparisonOperator.NOT_EXISTS)))
                .startsWith("NOT EXISTS (SELECT 1 FROM");
        assertThat(parameters.asMap()).isEmpty();
    }

    @Test
    void compile_subscribedByWithAnInapplicableOperator_throws() {
        FilterNode.Comparison comparison = field("subscribedBy", ComparisonOperator.CONTAINS, "EN");
        assertThatThrownBy(() -> compile(comparison))
                .isInstanceOf(FilterCompilationException.class)
                .hasMessageContaining("does not apply to the field 'subscribedBy'");
    }

    // -----------------------------------------------------------------------------------------
    // Attributes: rows of the live-value view
    // -----------------------------------------------------------------------------------------

    /** {@code FROM ... WHERE ...} as the compiler writes it for a product-scoped attribute. */
    private static String productRows(String scopeParameter, String nameParameter) {
        return " FROM policy_attribute_live_value a WHERE a.entity_id = p.id"
                + " AND a.scope_code = " + scopeParameter
                + " AND a.name = " + nameParameter;
    }

    @Test
    void compile_attributeIn_isAnExistsOverTheLiveValues() {
        String sql = compile(attribute("identifiability", ComparisonOperator.IN, "anonymised", "aggregated"));

        assertThat(sql).isEqualTo("EXISTS (SELECT 1" + productRows(":p0", ":p1") + " AND a.item IN (:p2))");
        assertThat(parameters.asMap())
                .containsExactly(
                        entry("p0", "PRODUCT"),
                        entry("p1", "identifiability"),
                        entry("p2", Set.of("anonymised", "aggregated")));
    }

    /** Attribute values are compared as stored, so their case is not folded the way a column's is. */
    @Test
    void compile_attributeEq_keepsTheValueAsGiven() {
        compile(attribute("quality_designation", ComparisonOperator.EQ, "Official Statistic"));

        assertThat(parameters.asMap()).containsEntry("p2", Set.of("Official Statistic"));
    }

    @Test
    void compile_attributeNoneOf_isANotExists() {
        assertThat(compile(attribute("population_risk_tags", ComparisonOperator.NONE_OF, "reidentifiable")))
                .isEqualTo("NOT EXISTS (SELECT 1" + productRows(":p0", ":p1") + " AND a.item IN (:p2))");
    }

    @Test
    void compile_attributeAllOf_countsTheDistinctItemsMatched() {
        String sql = compile(attribute("coverage_jurisdictions", ComparisonOperator.ALL_OF, "England", "Wales"));

        assertThat(sql)
                .isEqualTo(
                        "(SELECT COUNT(DISTINCT a.item)" + productRows(":p0", ":p1") + " AND a.item IN (:p2)) = :p3");
        assertThat(parameters.asMap()).containsEntry("p3", 2);
    }

    @Test
    void compile_attributeWithEmptyValueList_failsClosedForPositivesAndOpenForNegatives() {
        assertThat(compile(new FilterNode.Comparison(
                        FilterTarget.ofAttribute("tags"), ComparisonOperator.ANY_OF, List.of())))
                .isEqualTo("1 = 0");
        assertThat(compile(new FilterNode.Comparison(
                        FilterTarget.ofAttribute("tags"), ComparisonOperator.NOT_IN, List.of())))
                .isEqualTo("1 = 1");
        assertThat(compile(new FilterNode.Comparison(
                        FilterTarget.ofAttribute("tags"), ComparisonOperator.ALL_OF, List.of())))
                .isEqualTo("1 = 1");
    }

    @Test
    void compile_attributeContains_isAnEscapedLikeOnTheItem() {
        assertThat(compile(attribute("licence", ComparisonOperator.CONTAINS, "Open")))
                .isEqualTo("EXISTS (SELECT 1" + productRows(":p0", ":p1") + " AND LOWER(a.item) LIKE :p2 ESCAPE '\\')");
        assertThat(parameters.asMap()).containsEntry("p2", "%open%");
    }

    /** The cast must never run on a value that is not a number, whatever order the AND is evaluated in. */
    @Test
    void compile_attributeGte_castsOnlyValuesTypedAsNumbers() {
        assertThat(compile(attribute("refresh_days", ComparisonOperator.GTE, 7)))
                .isEqualTo("EXISTS (SELECT 1" + productRows(":p0", ":p1")
                        + " AND CASE WHEN a.item_type = 'number' THEN CAST(a.item AS numeric) END >= :p2)");
        assertThat(parameters.asMap()).containsEntry("p2", new BigDecimal("7"));
    }

    @Test
    void compile_attributeOrderingOperators_useTheMatchingSqlComparison() {
        assertThat(compile(attribute("refresh_days", ComparisonOperator.LT, 1))).contains("END < :p2");
        assertThat(compiler.compile(attribute("refresh_days", ComparisonOperator.LTE, 1), new SqlParameters()))
                .contains("END <= :p2");
        assertThat(compiler.compile(attribute("refresh_days", ComparisonOperator.GT, 1), new SqlParameters()))
                .contains("END > :p2");
    }

    @Test
    void compile_orderingOperatorWithANonNumericValue_throws() {
        FilterNode.Comparison comparison = attribute("refresh_days", ComparisonOperator.GT, "soon");
        assertThatThrownBy(() -> compile(comparison))
                .isInstanceOf(FilterCompilationException.class)
                .hasMessageContaining("needs a number, not 'soon'");
    }

    @Test
    void compile_attributeExistsAndNotExists_askOnlyWhetherARowIsThere() {
        assertThat(compile(attribute("identifiability", ComparisonOperator.EXISTS)))
                .isEqualTo("EXISTS (SELECT 1" + productRows(":p0", ":p1") + " AND 1 = 1)");
        assertThat(compile(attribute("identifiability", ComparisonOperator.NOT_EXISTS)))
                .isEqualTo("NOT EXISTS (SELECT 1" + productRows(":p2", ":p3") + " AND 1 = 1)");
    }

    @Test
    void compile_organisationScopedAttribute_readsTheOwningOrganisationsRows() {
        String sql = compile(new FilterNode.Comparison(
                new FilterTarget(FilterScope.ORGANISATION, false, "authorised_classifications"),
                ComparisonOperator.IN,
                List.of("SECRET")));

        assertThat(sql)
                .isEqualTo("EXISTS (SELECT 1 FROM policy_attribute_live_value a WHERE a.entity_id = o.id"
                        + " AND a.scope_code = :p0 AND a.name = :p1 AND a.item IN (:p2))");
        assertThat(parameters.asMap())
                .containsExactly(
                        entry("p0", "ORGANISATION"),
                        entry("p1", "authorised_classifications"),
                        entry("p2", Set.of("SECRET")));
    }

    @Test
    void compile_productScopedAttribute_readsTheProductsRows() {
        compile(attribute("identifiability", ComparisonOperator.EXISTS));

        assertThat(parameters.asMap()).containsEntry("p0", "PRODUCT");
    }

    /**
     * The one guarantee that lets a new attribute need no code change: its name is data, so nothing
     * a policy or a caller writes there can become SQL.
     */
    @Test
    void compile_attributeName_isBoundAndNeverInterpolated() {
        String hostile = "identifiability'); DROP TABLE product; --";

        String sql = compile(attribute(hostile, ComparisonOperator.IN, "x"));

        assertThat(sql)
                .isEqualTo("EXISTS (SELECT 1" + productRows(":p0", ":p1") + " AND a.item IN (:p2))")
                .doesNotContain("DROP TABLE", "--", "'");
        assertThat(parameters.asMap()).containsEntry("p1", hostile);
    }

    @Test
    void compile_hostileValue_staysAParameter() {
        String hostile = "' OR 1=1 --";

        String sql = compile(field("topic", ComparisonOperator.EQ, hostile));

        assertThat(sql).isEqualTo("LOWER(p.topic) IN (:p0)");
        assertThat(parameters.asMap()).containsEntry("p0", Set.of(hostile.toLowerCase(java.util.Locale.ROOT)));
    }

    // -----------------------------------------------------------------------------------------
    // LIKE escaping
    // -----------------------------------------------------------------------------------------

    @Test
    void compile_containsValueWithLikeWildcards_escapesThemInTheBoundPattern() {
        String sql = compile(field("name", ComparisonOperator.CONTAINS, "100%_A\\B"));

        assertThat(sql).endsWith(" ESCAPE '\\'");
        assertThat(parameters.asMap()).containsExactly(entry("p0", "%100\\%\\_a\\\\b%"));
    }

    // -----------------------------------------------------------------------------------------
    // Schema prefix
    // -----------------------------------------------------------------------------------------

    @Test
    void compile_withASchemaPrefix_qualifiesEveryTableItNames() {
        SqlPredicateCompiler schemaCompiler = new SqlPredicateCompiler("\"mn\".");

        assertThat(schemaCompiler.compile(attribute("identifiability", ComparisonOperator.EXISTS), parameters))
                .isEqualTo("EXISTS (SELECT 1 FROM \"mn\".policy_attribute_live_value a WHERE a.entity_id = p.id"
                        + " AND a.scope_code = :p0 AND a.name = :p1 AND 1 = 1)");
    }

    @Test
    void compile_withoutASchema_namesTablesBare() {
        assertThat(new DiscoverySchema("").prefix()).isEmpty();
        assertThat(compile(attribute("identifiability", ComparisonOperator.EXISTS)))
                .contains(" FROM policy_attribute_live_value a ");
    }
}
