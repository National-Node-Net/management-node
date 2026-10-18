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

        String expectedFragment = "WHERE (EXISTS (SELECT 1 FROM \"mn\".policy_attribute_live_value a";
        for (String branch : branchesOf(query)) {
            assertThat(branch).contains(expectedFragment);
        }
        assertThat(countOccurrences(query.sql(), "a.name = ")).isGreaterThanOrEqualTo(1);
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
                .contains("GROUP BY fo.organisation_key");
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
