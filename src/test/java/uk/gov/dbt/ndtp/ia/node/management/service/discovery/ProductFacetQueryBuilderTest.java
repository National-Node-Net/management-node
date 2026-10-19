/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.ComparisonOperator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterTarget;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;

/**
 * Covers the single statement {@link ProductFacetQueryBuilder} compiles for the catalogue's filter
 * panel: one {@code UNION ALL} branch per target, every branch carrying the contract's row filter,
 * and a branch's own counts narrowed by every other criteria filter and the free text but never by
 * its own target - which is what makes the counts disjunctive.
 */
class ProductFacetQueryBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProductFacetQueryBuilder builder = new ProductFacetQueryBuilder(new DiscoverySchema(""));
    private final ProductFacetQueryBuilder schemaBuilder = new ProductFacetQueryBuilder(new DiscoverySchema("mn"));

    private static final FilterOptionTarget FORMAT = fieldTarget("field:format", "format", ProductField.FORMAT);
    private static final FilterOptionTarget ORGANISATION_KEY =
            fieldTarget("field:organisation.key", "organisation.key", ProductField.ORGANISATION_KEY);
    private static final FilterOptionTarget SUBSCRIBED_BY =
            fieldTarget("field:subscribedBy", "subscribedBy", ProductField.SUBSCRIBED_BY);
    private static final FilterOptionTarget IS_OWN_PRODUCT =
            fieldTarget("field:isOwnProduct", "isOwnProduct", ProductField.IS_OWN_PRODUCT);
    private static final FilterOptionTarget PRODUCT_ATTRIBUTE = attributeTarget(
            "attribute:product.record_unit", new FilterTarget(FilterScope.PRODUCT, false, "record_unit"));
    private static final FilterOptionTarget ORGANISATION_ATTRIBUTE =
            attributeTarget("attribute:organisation.tier", new FilterTarget(FilterScope.ORGANISATION, false, "tier"));

    private static FilterOptionTarget fieldTarget(String key, String apiName, ProductField field) {
        return new FilterOptionTarget(
                key,
                FilterTarget.parse(null, true, apiName),
                field,
                apiName,
                "TEXT",
                false,
                List.of(ComparisonOperator.IN),
                ComparisonOperator.IN,
                true,
                true);
    }

    private static FilterOptionTarget attributeTarget(String key, FilterTarget target) {
        return new FilterOptionTarget(
                key,
                target,
                null,
                target.name(),
                "TEXT",
                false,
                List.of(ComparisonOperator.IN),
                ComparisonOperator.IN,
                true,
                true);
    }

    private static ProductSearchContract enforcing(String detailsJson) {
        try {
            ProductDiscoveryPolicyDecisionDetails details =
                    MAPPER.readValue(detailsJson, ProductDiscoveryPolicyDecisionDetails.class);
            PolicyDecision<ProductDiscoveryPolicyDecisionDetails> decision = PolicyDecision.of(
                            true, ProductDiscoveryPolicyDecisionDetails.class)
                    .withDetails(details);
            return ProductSearchContract.enforcing(decision);
        } catch (Exception e) {
            throw new IllegalStateException("The test's details JSON does not bind", e);
        }
    }

    private static ProductSearchCriteria criteria(String text, List<FilterNode.Comparison> filters) {
        return new ProductSearchCriteria(text, filters, List.of(), 0, 20);
    }

    private static List<String> branchesOf(FacetQuery query) {
        return List.of(query.sql().split(" UNION ALL "));
    }

    // -----------------------------------------------------------------------------------------

    @Test
    void eachTargetIsOneBranch_joinedByUnionAll() {
        FacetQuery query = builder.build(
                ProductSearchContract.open(), criteria(null, List.of()), List.of(FORMAT, ORGANISATION_KEY), null);

        String[] branches = query.sql().split(" UNION ALL ");
        assertThat(branches).hasSize(2);
        assertThat(countOccurrences(query.sql(), " UNION ALL ")).isEqualTo(1);
        for (String branch : branches) {
            assertThat(branch).startsWith("SELECT CAST(:p");
            assertThat(branch).contains("GROUP BY");
        }
    }

    @Test
    void rowFilterIsAlwaysApplied_andCompiledOnce() {
        ProductSearchContract contract = enforcing(
                """
                {"row_filter": {"type": "comparison", "attribute": "identifiability",
                                "operator": "in", "values": ["anonymised"]}}""");

        FacetQuery query =
                schemaBuilder.build(contract, criteria(null, List.of()), List.of(FORMAT, ORGANISATION_KEY), null);

        // The row filter is compiled once, independently here with a fresh SqlParameters that
        // starts at :p0 exactly as the builder's own does (its row filter is the first thing it
        // compiles), so this is the identical SQL text every branch's outer WHERE must carry.
        SqlPredicateCompiler rowFilterCompiler = new SqlPredicateCompiler(new DiscoverySchema("mn").prefix(), null);
        String compiledRowFilter = rowFilterCompiler.compile(contract.rowFilter(), new SqlParameters());
        String expectedWhere = " WHERE (" + compiledRowFilter + ")";

        List<String> branches = branchesOf(query);
        assertThat(countOccurrences(query.sql(), expectedWhere)).isEqualTo(branches.size());
        long identifiabilityBindings = query.parameters().values().stream()
                .filter("identifiability"::equals)
                .count();
        assertThat(identifiabilityBindings).isEqualTo(1);
    }

    @Test
    void aTargetsOwnFiltersAreExcludedFromItsCounts_othersIncluded() {
        ProductSearchCriteria criteria = criteria(
                null,
                List.of(
                        new FilterNode.Comparison(
                                FilterTarget.parse(null, true, "organisation.key"),
                                ComparisonOperator.EQ,
                                List.of("ENV")),
                        FilterNode.Comparison.ofField("format", ComparisonOperator.IN, "CSV")));

        FacetQuery query =
                builder.build(ProductSearchContract.open(), criteria, List.of(ORGANISATION_KEY, FORMAT), null);

        List<String> branches = branchesOf(query);
        String organisationBranch = branches.get(0);
        String formatBranch = branches.get(1);

        String organisationFilter = extractFilter(organisationBranch);
        String formatFilter = extractFilter(formatBranch);

        assertThat(organisationFilter).contains("LOWER(p.format) IN").doesNotContain("LOWER(o.organisation_key) IN");
        assertThat(formatFilter).contains("LOWER(o.organisation_key) IN").doesNotContain("LOWER(p.format) IN");
    }

    /**
     * Regression for the targets {@link FilterOptionTargets#permitted} actually hands this
     * builder, not the hand-built fixtures above: {@code organisation.key} is the one name where a
     * wrongly-built target would silently stop excluding itself, because its criteria-side
     * {@link FilterTarget} and a naively field-built one are not equal.
     */
    @Test
    void organisationKeyFilter_isExcludedFromItsOwnGroup() {
        List<FilterOptionTarget> permitted =
                FilterOptionTargets.permitted(ProductSearchContract.open(), List.of(), Set.of(), "ENV");
        FilterOptionTarget organisationKey = permitted.stream()
                .filter(target -> target.key().equals("field:organisation.key"))
                .findFirst()
                .orElseThrow();
        FilterOptionTarget format = permitted.stream()
                .filter(target -> target.key().equals("field:format"))
                .findFirst()
                .orElseThrow();
        ProductSearchCriteria criteria = criteria(
                null,
                List.of(new FilterNode.Comparison(
                        FilterTarget.parse(null, true, "organisation.key"), ComparisonOperator.EQ, List.of("ENV"))));

        FacetQuery query =
                builder.build(ProductSearchContract.open(), criteria, List.of(organisationKey, format), null);

        List<String> branches = branchesOf(query);
        assertThat(extractFilter(branches.get(0))).doesNotContain("LOWER(o.organisation_key) IN");
        assertThat(extractFilter(branches.get(1))).contains("LOWER(o.organisation_key) IN");
    }

    @Test
    void twoFiltersOnTheSameTarget_areBothExcludedFromItsOwnCounts() {
        ProductSearchCriteria criteria = criteria(
                null,
                List.of(
                        FilterNode.Comparison.ofField("format", ComparisonOperator.IN, "CSV"),
                        FilterNode.Comparison.ofField("format", ComparisonOperator.NEQ, "XML"),
                        new FilterNode.Comparison(
                                FilterTarget.parse(null, true, "organisation.key"),
                                ComparisonOperator.EQ,
                                List.of("ENV"))));

        FacetQuery query =
                builder.build(ProductSearchContract.open(), criteria, List.of(FORMAT, ORGANISATION_KEY), null);

        List<String> branches = branchesOf(query);
        String formatFilter = extractFilter(branches.get(0));
        String organisationFilter = extractFilter(branches.get(1));

        assertThat(formatFilter).doesNotContain("p.format");
        assertThat(organisationFilter)
                .contains("LOWER(p.format) IN")
                .contains("p.format IS NULL OR LOWER(p.format) NOT IN");
    }

    private static String extractFilter(String branch) {
        int start = branch.indexOf("FILTER (WHERE ") + "FILTER (WHERE ".length();
        int end = branch.indexOf(") AS matching");
        return branch.substring(start, end);
    }

    @Test
    void textNarrowsEveryBranch_withEscapedPattern() {
        ProductSearchContract contract = enforcing(
                """
                {"visible_fields": ["name"], "text_search_fields": ["name"],
                 "row_filter": {"type": "literal", "value": true}}""");
        ProductSearchCriteria criteria = criteria("Bristol_Cycle%Counts", List.of());

        FacetQuery query = builder.build(contract, criteria, List.of(FORMAT, ORGANISATION_KEY), null);

        long patternBindings = query.parameters().values().stream()
                .filter("%bristol\\_cycle\\%counts%"::equals)
                .count();
        assertThat(patternBindings).isEqualTo(1);
        for (String branch : branchesOf(query)) {
            assertThat(extractFilter(branch)).contains("ESCAPE '\\'");
        }
    }

    @Test
    void subscribedByCountsDistinctProductsThroughLeftJoinedGrants() {
        FacetQuery query = schemaBuilder.build(
                ProductSearchContract.open(), criteria(null, List.of()), List.of(SUBSCRIBED_BY), null);

        assertThat(query.sql())
                .contains("CAST(fo.organisation_key AS TEXT) AS value")
                .contains("COUNT(DISTINCT p.id) FILTER")
                .contains(" LEFT JOIN \"mn\".product_consumer fpc ON fpc.product_id = p.id")
                .contains(" LEFT JOIN \"mn\".consumer fc ON fc.id = fpc.consumer_id")
                .contains(" LEFT JOIN \"mn\".organisation fo ON fo.id = fc.org_id")
                .contains("GROUP BY 2");
    }

    @Test
    void isOwnProductBranch_comparesTheOwnerWithTheCaller() {
        FacetQuery query =
                builder.build(ProductSearchContract.open(), criteria(null, List.of()), List.of(IS_OWN_PRODUCT), "ENV");

        assertThat(query.sql())
                .contains("CASE WHEN LOWER(o.organisation_key) = :p")
                .contains("THEN 'true' ELSE 'false' END");
        assertThat(query.parameters()).containsValue("env");
    }

    /**
     * A blank caller normalises to "not known", the same as {@link SqlPredicateCompiler}'s own
     * constructor does for the page query: no product counts as anyone's own, and nothing is
     * bound for it.
     */
    @Test
    void blankCaller_isOwnProductBranch_comparesAsUnknown() {
        FacetQuery query =
                builder.build(ProductSearchContract.open(), criteria(null, List.of()), List.of(IS_OWN_PRODUCT), " ");

        assertThat(query.sql()).contains("CASE WHEN 1 = 0 THEN 'true' ELSE 'false' END");
        assertThat(query.parameters()).hasSize(1).containsEntry("p0", "field:isOwnProduct");
    }

    /**
     * {@code GROUP BY} names the value column by position rather than repeating its expression,
     * in every branch - not only {@code isOwnProduct}'s, whose {@code CASE} is the one value
     * expression that binds a parameter and so is the one PostgreSQL would reject if grouped by
     * expression (a fresh placeholder for the same bound value makes the {@code SELECT} and
     * {@code GROUP BY} occurrences look like two different expressions to it).
     */
    @Test
    void noBranchGroupsByAnExpressionHoldingAParameter() {
        FacetQuery query = builder.build(
                ProductSearchContract.open(),
                criteria(null, List.of()),
                List.of(FORMAT, SUBSCRIBED_BY, IS_OWN_PRODUCT, PRODUCT_ATTRIBUTE),
                "ENV");

        List<String> branches = branchesOf(query);
        assertThat(branches).hasSize(4);
        for (String branch : branches) {
            assertThat(branch).endsWith("GROUP BY 2");
        }
        assertThat(countOccurrences(query.sql(), "GROUP BY 2")).isEqualTo(branches.size());
        assertThat(query.sql()).doesNotContain("GROUP BY :p");
    }

    @Test
    void attributeBranch_leftJoinsTheLiveValueViewOnTheScopesOwner() {
        FacetQuery productQuery = builder.build(
                ProductSearchContract.open(), criteria(null, List.of()), List.of(PRODUCT_ATTRIBUTE), null);
        FacetQuery organisationQuery = builder.build(
                ProductSearchContract.open(), criteria(null, List.of()), List.of(ORGANISATION_ATTRIBUTE), null);

        assertThat(productQuery.sql())
                .contains("LEFT JOIN policy_attribute_live_value fa ON fa.entity_id = p.id")
                .contains("CAST(fa.item AS TEXT) AS value");
        assertThat(organisationQuery.sql()).contains("LEFT JOIN policy_attribute_live_value fa ON fa.entity_id = o.id");

        assertThat(productQuery.parameters()).containsValue("PRODUCT").containsValue("record_unit");
        assertThat(organisationQuery.parameters()).containsValue("ORGANISATION").containsValue("tier");
        assertThat(productQuery.sql()).doesNotContain("'record_unit'").doesNotContain("'PRODUCT'");
    }

    @Test
    void emptyTargets_isRejected() {
        assertThatThrownBy(
                        () -> builder.build(ProductSearchContract.open(), criteria(null, List.of()), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void targetThatDoesNotListValues_isRejected() {
        FilterOptionTarget noValues = new FilterOptionTarget(
                "field:name",
                FilterTarget.ofField("name"),
                ProductField.NAME,
                "Name",
                "TEXT",
                false,
                List.of(ComparisonOperator.CONTAINS),
                ComparisonOperator.CONTAINS,
                true,
                false);

        assertThatThrownBy(() ->
                        builder.build(ProductSearchContract.open(), criteria(null, List.of()), List.of(noValues), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void schemaPrefixIsAppliedToEveryTable() {
        FacetQuery query = schemaBuilder.build(
                ProductSearchContract.open(),
                criteria(null, List.of()),
                List.of(FORMAT, SUBSCRIBED_BY, PRODUCT_ATTRIBUTE),
                null);

        assertThat(query.sql())
                .contains("FROM \"mn\".product p")
                .contains("JOIN \"mn\".producer pr ON pr.id = p.producer_id")
                .contains("JOIN \"mn\".organisation o ON o.id = pr.org_id")
                .contains("LEFT JOIN \"mn\".product_type pt ON pt.id = p.product_type_id")
                .contains("LEFT JOIN \"mn\".product_consumer fpc ON fpc.product_id = p.id")
                .contains("LEFT JOIN \"mn\".consumer fc ON fc.id = fpc.consumer_id")
                .contains("LEFT JOIN \"mn\".organisation fo ON fo.id = fc.org_id")
                .contains("LEFT JOIN \"mn\".policy_attribute_live_value fa ON fa.entity_id = p.id");
    }

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
