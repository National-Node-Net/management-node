/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.ComparisonOperator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;

/**
 * Covers the statement built from one decision and one set of caller criteria: that the
 * {@code SELECT} list is exactly what the contract makes visible - a masked column never appearing
 * in the SQL at all - that the policy condition is AND-ed with the caller's and so cannot be
 * widened by it, and the shape of the text fragment, the ordering, the paging and the count.
 */
class ProductSearchQueryBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ProductSearchQueryBuilder builder = new ProductSearchQueryBuilder(new DiscoverySchema(""));
    private final ProductSearchQueryBuilder schemaBuilder = new ProductSearchQueryBuilder(new DiscoverySchema("mn"));

    private static final String FROM = " FROM product p"
            + " JOIN producer pr ON pr.id = p.producer_id"
            + " JOIN organisation o ON o.id = pr.org_id"
            + " LEFT JOIN product_type pt ON pt.id = p.product_type_id";

    private static final String IDS = "p.id AS id, o.id AS organisation_id, pr.id AS producer_id";

    /** The contract a rule granting {@code detailsJson} produces. */
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

    private static ProductSearchCriteria criteria() {
        return criteria(null, List.of());
    }

    private static ProductSearchCriteria criteria(String text, List<FilterNode.Comparison> filters) {
        return new ProductSearchCriteria(
                text, filters, List.of(ProductSearchCriteria.SortKey.byField(ProductField.NAME, false)), 0, 20);
    }

    private static String whereOf(ProductSearchQuery query) {
        String sql = query.pageSql();
        return sql.substring(sql.indexOf(" WHERE "), sql.indexOf(" ORDER BY "));
    }

    /** The ordering and paging tail, i.e. everything the {@code WHERE} is followed by. */
    private static String orderingAndPagingOf(ProductSearchQuery query) {
        String sql = query.pageSql();
        return sql.substring(sql.indexOf(" ORDER BY "));
    }

    /** The columns the page query reads, i.e. everything before the {@code FROM}. */
    private static String selectListOf(ProductSearchQuery query) {
        String sql = query.pageSql();
        return sql.substring("SELECT ".length(), sql.indexOf(" FROM "));
    }

    // -----------------------------------------------------------------------------------------
    // The SELECT list is built from the decision
    // -----------------------------------------------------------------------------------------

    @Test
    void build_openContract_selectsEverySelectableField() {
        ProductSearchCriteria criteria = new ProductSearchCriteria(
                null, List.of(), List.of(ProductSearchCriteria.SortKey.byField(ProductField.NAME, false)), 2, 25);

        ProductSearchQuery query = builder.build(ProductSearchContract.open(), criteria, ProductProjection.FULL);

        assertThat(query.pageSql())
                .isEqualTo("SELECT " + IDS
                        + ", p.name AS name, p.description AS description, p.topic AS topic, pt.name AS type,"
                        + " p.source AS source, o.organisation_key AS organisation_key, o.name AS organisation_name,"
                        + " pr.name AS producer_name, pr.description AS producer_description,"
                        + " pr.active AS producer_active"
                        + FROM
                        + " WHERE (1 = 1) AND (1 = 1) AND (1 = 1)"
                        + " ORDER BY LOWER(p.name) ASC NULLS LAST, p.id ASC LIMIT :p0 OFFSET :p1");
        assertThat(query.parameters()).containsExactly(entry("p0", 25), entry("p1", 50L));
    }

    @Test
    void build_enforcingContract_selectsTheVisibleFieldsAndTheIds() {
        ProductSearchContract contract = enforcing(
                """
                {"visible_fields": ["name", "topic", "organisation"],
                 "masked_filtered_fields": ["organisation.name"],
                 "row_filter": {"type": "literal", "value": true}}""");

        ProductSearchQuery query = builder.build(contract, criteria(), ProductProjection.FULL);

        assertThat(query.pageSql())
                .startsWith("SELECT " + IDS
                        + ", p.name AS name, p.topic AS topic, o.organisation_key AS organisation_key FROM ");
    }

    /** The central guarantee: what the caller may not see is never read out of the database. */
    @Test
    void build_maskedField_neverAppearsAnywhereInTheStatement() {
        ProductSearchContract contract = enforcing(
                """
                {"visible_fields": ["name", "organisation"],
                 "masked_filtered_fields": ["organisation.name"],
                 "text_search_fields": ["name"],
                 "row_filter": {"type": "literal", "value": true}}""");

        ProductSearchQuery query = schemaBuilder.build(contract, criteria("plan", List.of()), ProductProjection.FULL);

        assertThat(query.pageSql()).doesNotContain("o.name");
        assertThat(query.countSql()).doesNotContain("o.name");
        assertThat(query.pageSql()).doesNotContain("p.description", "p.source", "p.topic", "pt.name", "pr.name");
    }

    @Test
    void build_enforcingContractWithoutVisibleFields_selectsTheIdsOnly() {
        ProductSearchContract contract = enforcing("{\"row_filter\": {\"type\": \"literal\", \"value\": true}}");

        ProductSearchQuery query = builder.build(contract, criteria(), ProductProjection.FULL);

        assertThat(query.pageSql()).startsWith("SELECT " + IDS + FROM);
    }

    @Test
    void build_withASchema_qualifiesEveryTable() {
        ProductSearchQuery query =
                schemaBuilder.build(ProductSearchContract.open(), criteria(), ProductProjection.FULL);

        assertThat(query.pageSql())
                .contains(" FROM \"mn\".product p JOIN \"mn\".producer pr ON pr.id = p.producer_id"
                        + " JOIN \"mn\".organisation o ON o.id = pr.org_id"
                        + " LEFT JOIN \"mn\".product_type pt ON pt.id = p.product_type_id");
    }

    // -----------------------------------------------------------------------------------------
    // WHERE: policy AND caller AND text
    // -----------------------------------------------------------------------------------------

    @Test
    void build_withPolicyCallerAndTextConditions_joinsThemWithAnd() {
        ProductSearchContract contract = enforcing(
                """
                {"visible_fields": ["name", "description"],
                 "text_search_fields": ["name", "description"],
                 "row_filter": {"type": "comparison", "field": "type", "operator": "eq", "values": ["topic"]}}""");
        ProductSearchCriteria criteria = criteria(
                "pl%an",
                List.of(
                        FilterNode.Comparison.ofField("topic", ComparisonOperator.EQ, "Weather"),
                        FilterNode.Comparison.ofAttribute("identifiability", ComparisonOperator.IN, "anonymised")));

        ProductSearchQuery query = builder.build(contract, criteria, ProductProjection.FULL);

        assertThat(whereOf(query))
                .isEqualTo(" WHERE (LOWER(pt.name) IN (:p0))"
                        + " AND ((LOWER(p.topic) IN (:p1) AND EXISTS (SELECT 1 FROM policy_attribute_live_value a"
                        + " WHERE a.entity_id = p.id AND a.scope_code = :p2 AND a.name = :p3"
                        + " AND a.item IN (:p4))))"
                        + " AND (LOWER(p.name) LIKE :p5 ESCAPE '\\'"
                        + " OR LOWER(p.description) LIKE :p5 ESCAPE '\\')");
        assertThat(query.parameters())
                .containsExactly(
                        entry("p0", Set.of("topic")),
                        entry("p1", Set.of("weather")),
                        entry("p2", "PRODUCT"),
                        entry("p3", "identifiability"),
                        entry("p4", Set.of("anonymised")),
                        entry("p5", "%pl\\%an%"),
                        entry("p6", 20),
                        entry("p7", 0L));
    }

    /**
     * The caller's condition is compiled into its own parenthesised group, so no caller input can
     * be placed where it would OR with what policy requires.
     */
    @Test
    void build_callerFilters_cannotWidenThePolicyCondition() {
        ProductSearchContract contract = enforcing(
                """
                {"row_filter": {"type": "comparison", "field": "organisation.key",
                                "operator": "eq", "values": ["ENV"]}}""");
        ProductSearchCriteria criteria =
                criteria(null, List.of(FilterNode.Comparison.ofField("topic", ComparisonOperator.NONE_OF, "secret")));

        ProductSearchQuery query = builder.build(contract, criteria, ProductProjection.FULL);

        assertThat(whereOf(query))
                .isEqualTo(" WHERE (LOWER(o.organisation_key) IN (:p0))"
                        + " AND (((p.topic IS NULL OR LOWER(p.topic) NOT IN (:p1))))"
                        + " AND (1 = 1)");
    }

    @Test
    void build_deniedDecision_findsNothing() {
        PolicyDecision<ProductDiscoveryPolicyDecisionDetails> refused =
                PolicyDecision.of(false, ProductDiscoveryPolicyDecisionDetails.class);

        ProductSearchQuery query =
                builder.build(ProductSearchContract.enforcing(refused), criteria(), ProductProjection.FULL);

        assertThat(whereOf(query)).isEqualTo(" WHERE (1 = 0) AND (1 = 1) AND (1 = 1)");
    }

    // -----------------------------------------------------------------------------------------
    // The view endpoint: a search constrained to one product
    // -----------------------------------------------------------------------------------------

    /**
     * What {@code GET /api/v1/product/{productId}} builds: no text, no sort, one filter - the id -
     * and a page of one. The id condition is AND-ed onto the policy row filter like any other
     * caller condition, so it can only narrow it. That is the whole of why an id cannot reach a
     * product discovery would have withheld.
     */
    @Test
    void build_viewCriteria_andsTheIdConditionOntoThePolicyRowFilterAndAsksForOneRow() {
        ProductSearchContract contract = enforcing(
                """
                {"visible_fields": ["name"],
                 "row_filter": {"type": "comparison", "field": "organisation.key",
                                "operator": "eq", "values": ["ENV"]}}""");
        ProductSearchCriteria view = new ProductSearchCriteria(
                null, List.of(FilterNode.Comparison.ofField("id", ComparisonOperator.EQ, 7L)), List.of(), 0, 1);

        ProductSearchQuery query = builder.build(contract, view, ProductProjection.FULL);

        assertThat(query.pageSql())
                .isEqualTo("SELECT " + IDS + ", p.name AS name" + FROM
                        + " WHERE (LOWER(o.organisation_key) IN (:p0))"
                        + " AND ((p.id IN (:p1)))"
                        + " AND (1 = 1)"
                        + " ORDER BY p.id ASC LIMIT :p2 OFFSET :p3");
        assertThat(query.parameters())
                .containsExactly(
                        entry("p0", Set.of("env")),
                        entry("p1", Set.of(new BigDecimal("7"))),
                        entry("p2", 1),
                        entry("p3", 0L));
    }

    /**
     * The id arrives in the caller half of the {@code WHERE}, in its own parenthesised group. A
     * denying row filter therefore still denies: nothing the endpoint adds can displace or widen
     * the policy fragment, so asking by id is not a way round a refusal.
     */
    @Test
    void build_viewCriteria_cannotDisplaceADenyingPolicyFragment() {
        PolicyDecision<ProductDiscoveryPolicyDecisionDetails> refused =
                PolicyDecision.of(false, ProductDiscoveryPolicyDecisionDetails.class);
        ProductSearchCriteria view = new ProductSearchCriteria(
                null, List.of(FilterNode.Comparison.ofField("id", ComparisonOperator.EQ, 7L)), List.of(), 0, 1);

        ProductSearchQuery query =
                builder.build(ProductSearchContract.enforcing(refused), view, ProductProjection.FULL);

        assertThat(whereOf(query)).isEqualTo(" WHERE (1 = 0) AND ((p.id IN (:p0))) AND (1 = 1)");
    }

    // -----------------------------------------------------------------------------------------
    // The text fragment
    // -----------------------------------------------------------------------------------------

    @Test
    void build_withoutText_matchesEveryProduct() {
        ProductSearchQuery query = builder.build(ProductSearchContract.open(), criteria(), ProductProjection.FULL);

        assertThat(whereOf(query)).endsWith(" AND (1 = 1)");
    }

    @Test
    void build_withTextAndNoPermittedTextField_matchesNoProduct() {
        ProductSearchContract contract = enforcing(
                """
                {"visible_fields": ["name"], "row_filter": {"type": "literal", "value": true}}""");

        ProductSearchQuery query = builder.build(contract, criteria("plan", List.of()), ProductProjection.FULL);

        assertThat(whereOf(query)).endsWith(" AND (1 = 0)");
        assertThat(query.parameters()).doesNotContainValue("%plan%");
    }

    @Test
    void build_withTextAndOnePermittedTextField_matchesThatFieldOnly() {
        ProductSearchContract contract = enforcing(
                """
                {"visible_fields": ["name", "description"],
                 "text_search_fields": ["name"],
                 "row_filter": {"type": "literal", "value": true}}""");

        ProductSearchQuery query = builder.build(contract, criteria("plan", List.of()), ProductProjection.FULL);

        assertThat(whereOf(query)).endsWith(" AND (LOWER(p.name) LIKE :p0 ESCAPE '\\')");
        assertThat(query.parameters()).containsEntry("p0", "%plan%");
    }

    // -----------------------------------------------------------------------------------------
    // ORDER BY
    // -----------------------------------------------------------------------------------------

    @Test
    void build_fieldSortKey_ordersByTheColumnAndTieBreaksOnTheId() {
        ProductSearchQuery query = builder.build(ProductSearchContract.open(), criteria(), ProductProjection.FULL);

        assertThat(query.pageSql()).contains(" ORDER BY LOWER(p.name) ASC NULLS LAST, p.id ASC LIMIT ");
    }

    @Test
    void build_descendingSortKey_ordersDescending() {
        ProductSearchCriteria criteria = new ProductSearchCriteria(
                null, List.of(), List.of(ProductSearchCriteria.SortKey.byField(ProductField.TOPIC, true)), 0, 20);

        ProductSearchQuery query = builder.build(ProductSearchContract.open(), criteria, ProductProjection.FULL);

        assertThat(query.pageSql()).contains(" ORDER BY LOWER(p.topic) DESC NULLS LAST, p.id ASC LIMIT ");
    }

    @Test
    void build_attributeSortKey_ordersByACorrelatedMinimum() {
        ProductSearchCriteria criteria = new ProductSearchCriteria(
                null,
                List.of(),
                List.of(ProductSearchCriteria.SortKey.byAttribute(FilterScope.PRODUCT, "quality_designation", false)),
                0,
                20);

        ProductSearchQuery query = schemaBuilder.build(ProductSearchContract.open(), criteria, ProductProjection.FULL);

        assertThat(query.pageSql())
                .contains(" ORDER BY (SELECT MIN(a.item) FROM \"mn\".policy_attribute_live_value a"
                        + " WHERE a.entity_id = p.id AND a.scope_code = :p0 AND a.name = :p1)"
                        + " ASC NULLS LAST, p.id ASC LIMIT ");
        assertThat(query.parameters()).containsEntry("p0", "PRODUCT").containsEntry("p1", "quality_designation");
    }

    @Test
    void build_organisationAttributeSortKey_ordersByTheOrganisationsValues() {
        ProductSearchCriteria criteria = new ProductSearchCriteria(
                null,
                List.of(),
                List.of(ProductSearchCriteria.SortKey.byAttribute(FilterScope.ORGANISATION, "tier", true)),
                0,
                20);

        ProductSearchQuery query = builder.build(ProductSearchContract.open(), criteria, ProductProjection.FULL);

        assertThat(query.pageSql())
                .contains(" ORDER BY (SELECT MIN(a.item) FROM policy_attribute_live_value a"
                        + " WHERE a.entity_id = o.id AND a.scope_code = :p0 AND a.name = :p1)"
                        + " DESC NULLS LAST, p.id ASC");
        assertThat(query.parameters()).containsEntry("p0", "ORGANISATION");
    }

    // -----------------------------------------------------------------------------------------
    // Paging and the count
    // -----------------------------------------------------------------------------------------

    @Test
    void build_paging_bindsTheSizeAndTheComputedOffset() {
        ProductSearchCriteria criteria = new ProductSearchCriteria(
                null, List.of(), List.of(ProductSearchCriteria.SortKey.byField(ProductField.NAME, false)), 3, 10);

        ProductSearchQuery query = builder.build(ProductSearchContract.open(), criteria, ProductProjection.FULL);

        assertThat(query.pageSql()).endsWith(" LIMIT :p0 OFFSET :p1");
        assertThat(query.parameters()).containsExactly(entry("p0", 10), entry("p1", 30L));
    }

    @Test
    void build_countStatement_sharesTheFromAndWhereAndHasNoOrderingOrPaging() {
        ProductSearchContract contract = enforcing(
                """
                {"visible_fields": ["name"],
                 "text_search_fields": ["name"],
                 "row_filter": {"type": "comparison", "attribute": "identifiability",
                                "operator": "in", "values": ["anonymised"]}}""");

        ProductSearchQuery query = schemaBuilder.build(contract, criteria("plan", List.of()), ProductProjection.FULL);

        String fromAndWhere = query.countSql().substring("SELECT COUNT(*)".length());
        assertThat(query.countSql()).startsWith("SELECT COUNT(*) FROM \"mn\".product p");
        assertThat(query.pageSql()).contains(fromAndWhere);
        assertThat(query.countSql()).doesNotContain("ORDER BY", "LIMIT", "OFFSET");
    }

    // -----------------------------------------------------------------------------------------
    // unmask_when
    // -----------------------------------------------------------------------------------------

    @Test
    void build_unmaskRules_addOneFlagColumnEachAndSelectWhatOnlyTheyShow() {
        ProductSearchContract contract = enforcing(
                """
                {"visible_fields": ["name"],
                 "row_filter": {"type": "literal", "value": true},
                 "unmask_when": [
                   {"names": ["organisation"],
                    "when": {"type": "comparison", "field": "organisation.key",
                             "operator": "eq", "values": ["BCC"]}},
                   {"names": ["consumers"], "when": {"type": "literal", "value": true}}]}""");

        ProductSearchQuery query = builder.build(contract, criteria(), ProductProjection.FULL);

        assertThat(query.pageSql())
                .startsWith("SELECT " + IDS + ", p.name AS name,"
                        + " CASE WHEN (LOWER(o.organisation_key) IN (:p0)) THEN o.organisation_key END"
                        + " AS organisation_key,"
                        + " CASE WHEN (LOWER(o.organisation_key) IN (:p0)) THEN o.name END AS organisation_name,"
                        + " CASE WHEN LOWER(o.organisation_key) IN (:p0) THEN TRUE ELSE FALSE END AS unmask_0,"
                        + " CASE WHEN 1 = 1 THEN TRUE ELSE FALSE END AS unmask_1 FROM ");
        assertThat(query.parameters()).containsEntry("p0", Set.of("bcc"));
    }

    @Test
    void build_withoutUnmaskRules_hasNoFlagColumn() {
        ProductSearchQuery query = builder.build(ProductSearchContract.open(), criteria(), ProductProjection.FULL);

        assertThat(query.pageSql()).doesNotContain(ProductSearchQuery.unmaskColumn(0));
    }

    // -----------------------------------------------------------------------------------------
    // The projection narrows the SELECT list, and nothing else
    // -----------------------------------------------------------------------------------------

    @Test
    void build_summaryProjection_selectsOnlyTheSearchResultColumns() {
        ProductSearchQuery query = builder.build(ProductSearchContract.open(), criteria(), ProductProjection.SUMMARY);

        // The contract permits everything; the projection is what leaves the rest out, and it does
        // so by not selecting the columns at all. The owning organisation's key and name are in,
        // because a caller needs to know whose product each result is.
        assertThat(selectListOf(query))
                .isEqualTo(IDS + ", p.name AS name, p.description AS description, pt.name AS type,"
                        + " o.organisation_key AS organisation_key, o.name AS organisation_name");
        assertThat(query.pageSql()).doesNotContain("p.source", "pr.name", "pr.description", "pr.active", "p.topic");
    }

    @Test
    void build_fullProjection_selectsTheColumnsSummaryWithholds() {
        ProductSearchQuery query = builder.build(ProductSearchContract.open(), criteria(), ProductProjection.FULL);

        // Same contract, same criteria as the SUMMARY test above: the projection is the only
        // difference between the two statements.
        assertThat(selectListOf(query))
                .contains(
                        "p.name AS name",
                        "p.description AS description",
                        "pt.name AS type",
                        "p.source AS source",
                        "o.organisation_key AS organisation_key",
                        "o.name AS organisation_name",
                        "pr.name AS producer_name",
                        "pr.description AS producer_description",
                        "pr.active AS producer_active");
    }

    /**
     * A projection is intersected with what policy permits, never added to it: a field the contract
     * masks stays out of the statement even though the projection returns it.
     */
    @Test
    void build_summaryProjectionAndAMaskedField_stillNeverSelectsThatField() {
        ProductSearchContract contract = enforcing(
                """
                {"visible_fields": ["name", "description", "type"],
                 "masked_filtered_fields": ["description"],
                 "row_filter": {"type": "literal", "value": true}}""");

        ProductSearchQuery query = builder.build(contract, criteria(), ProductProjection.SUMMARY);

        assertThat(selectListOf(query)).isEqualTo(IDS + ", p.name AS name, pt.name AS type");
        assertThat(query.pageSql()).doesNotContain("p.description");
        assertThat(query.countSql()).doesNotContain("p.description");
    }

    /**
     * Only the returned columns narrow. A search still filters, sorts and pages on everything policy
     * allows - including a field the projection does not return - and the count is the same
     * statement either way.
     */
    @Test
    void build_summaryProjection_leavesTheWhereOrderingPagingAndCountUnchanged() {
        ProductSearchContract contract = enforcing(
                """
                {"visible_fields": ["name", "description", "type", "source"],
                 "text_search_fields": ["name", "description"],
                 "row_filter": {"type": "comparison", "field": "type", "operator": "eq", "values": ["topic"]}}""");
        ProductSearchCriteria criteria = new ProductSearchCriteria(
                "plan",
                List.of(FilterNode.Comparison.ofField("source", ComparisonOperator.EQ, "https://example.test/flood")),
                List.of(ProductSearchCriteria.SortKey.byField(ProductField.SOURCE, false)),
                1,
                10);

        ProductSearchQuery summary = builder.build(contract, criteria, ProductProjection.SUMMARY);
        ProductSearchQuery full = builder.build(contract, criteria, ProductProjection.FULL);

        assertThat(whereOf(summary)).isEqualTo(whereOf(full)).contains("LOWER(p.source) IN (");
        assertThat(orderingAndPagingOf(summary))
                .isEqualTo(orderingAndPagingOf(full))
                .startsWith(" ORDER BY LOWER(p.source) ASC NULLS LAST, p.id ASC LIMIT ");
        assertThat(summary.countSql()).isEqualTo(full.countSql());
        assertThat(summary.parameters()).isEqualTo(full.parameters());
        // ... and the one difference: the sorted-on column is not among the ones read back.
        assertThat(summary.pageSql()).doesNotContain("p.source AS source");
        assertThat(full.pageSql()).contains("p.source AS source");
    }
}
