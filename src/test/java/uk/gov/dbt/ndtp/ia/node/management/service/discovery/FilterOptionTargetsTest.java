/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.ComparisonOperator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository.FilterAttributeDefinition;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyProvenance;

/**
 * Covers which filters {@link FilterOptionTargets#permitted} offers a caller: every filterable
 * field but {@code id}, every attribute the contract permits with the sensitive ones dropped when
 * masked, {@code isOwnProduct} only for a known caller, and the operator set and default each
 * target gets.
 */
class FilterOptionTargetsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final FilterAttributeDefinition QUALITY_DESIGNATION = new FilterAttributeDefinition(
            FilterScope.PRODUCT, "quality_designation", "Quality designation", "STRING", false, false);
    private static final FilterAttributeDefinition AUTHORISED_CLASSIFICATIONS = new FilterAttributeDefinition(
            FilterScope.ORGANISATION, "authorised_classifications", "Authorised classifications", "STRING", true, true);
    private static final FilterAttributeDefinition RECORD_UNIT =
            new FilterAttributeDefinition(FilterScope.PRODUCT, "record_unit", "Record unit", "STRING", false, false);

    @Test
    void openContract_listsEveryFilterableFieldButId_andEveryAttribute_sensitiveIncluded() {
        ProductSearchContract contract = ProductSearchContract.open();

        List<FilterOptionTarget> options = FilterOptionTargets.permitted(
                contract, List.of(QUALITY_DESIGNATION, AUTHORISED_CLASSIFICATIONS), "ENV");

        assertThat(options)
                .extracting(FilterOptionTarget::key)
                .containsExactly(
                        "field:name",
                        "field:displayName",
                        "field:description",
                        "field:topic",
                        "field:type",
                        "field:source",
                        "field:format",
                        "field:organisation.key",
                        "field:organisation.name",
                        "field:subscribedBy",
                        "field:isOwnProduct",
                        "attribute:product.quality_designation",
                        "attribute:organisation.authorised_classifications");
    }

    @Test
    void enforcedContract_listsOnlyWhatPolicyPermitsMinusMasked() throws Exception {
        ProductSearchContract contract = enforcing(
                """
                {"allowed_filtered_fields":["name","format","source"],
                 "masked_filtered_fields":["source"],
                 "allowed_filtered_attributes":["record_unit"]}
                """);

        List<FilterOptionTarget> options =
                FilterOptionTargets.permitted(contract, List.of(QUALITY_DESIGNATION, RECORD_UNIT), "ENV");

        assertThat(options)
                .extracting(FilterOptionTarget::key)
                .containsExactly("field:name", "field:format", "attribute:product.record_unit");
    }

    @Test
    void sensitiveAttributes_areDroppedWhenTheContractMasksThem() throws Exception {
        ProductSearchContract contract = enforcing(
                """
                {"allowed_filtered_fields":["name"],
                 "allowed_filtered_attributes":["quality_designation","authorised_classifications"],
                 "mask_sensitive_attributes":true}
                """);

        List<FilterOptionTarget> options = FilterOptionTargets.permitted(
                contract, List.of(QUALITY_DESIGNATION, AUTHORISED_CLASSIFICATIONS), "ENV");

        assertThat(options)
                .extracting(FilterOptionTarget::key)
                .containsExactly("field:name", "attribute:product.quality_designation");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {" ", "UNKNOWN_ORG"})
    void unknownCaller_omitsIsOwnProduct(String callerOrganisation) {
        ProductSearchContract contract = ProductSearchContract.open();

        List<FilterOptionTarget> options = FilterOptionTargets.permitted(contract, List.of(), callerOrganisation);

        assertThat(options).extracting(FilterOptionTarget::key).doesNotContain("field:isOwnProduct");
    }

    @Test
    void knownCaller_includesIsOwnProduct() {
        ProductSearchContract contract = ProductSearchContract.open();

        List<FilterOptionTarget> options = FilterOptionTargets.permitted(contract, List.of(), "ENV");

        assertThat(options).extracting(FilterOptionTarget::key).contains("field:isOwnProduct");
    }

    @Test
    void operatorsAndDefaults_followTheTypeOfEachTarget() {
        ProductSearchContract contract = ProductSearchContract.open();
        FilterAttributeDefinition multiValuedText =
                new FilterAttributeDefinition(FilterScope.PRODUCT, "tags", "Tags", "STRING", true, false);
        FilterAttributeDefinition singleValuedText = new FilterAttributeDefinition(
                FilterScope.PRODUCT, "classification", "Classification", "STRING", false, false);
        FilterAttributeDefinition numberAttribute = new FilterAttributeDefinition(
                FilterScope.PRODUCT, "retention_days", "Retention days", "NUMBER", false, false);

        List<FilterOptionTarget> options = FilterOptionTargets.permitted(
                contract, List.of(multiValuedText, singleValuedText, numberAttribute), "ENV");

        FilterOptionTarget name = option(options, "field:name");
        assertThat(name.defaultOperator()).isEqualTo(ComparisonOperator.CONTAINS);
        assertThat(name.listsValues()).isFalse();

        FilterOptionTarget format = option(options, "field:format");
        assertThat(format.defaultOperator()).isEqualTo(ComparisonOperator.IN);

        FilterOptionTarget subscribedBy = option(options, "field:subscribedBy");
        assertThat(subscribedBy.defaultOperator()).isEqualTo(ComparisonOperator.ANY_OF);
        assertThat(subscribedBy.operators()).doesNotContain(ComparisonOperator.CONTAINS);

        FilterOptionTarget isOwnProduct = option(options, "field:isOwnProduct");
        assertThat(isOwnProduct.defaultOperator()).isEqualTo(ComparisonOperator.EQ);
        assertThat(isOwnProduct.operators()).doesNotContain(ComparisonOperator.CONTAINS);

        FilterOptionTarget multiText = option(options, "attribute:product.tags");
        assertThat(multiText.defaultOperator()).isEqualTo(ComparisonOperator.ANY_OF);
        assertThat(multiText.operators()).contains(ComparisonOperator.ALL_OF);

        FilterOptionTarget numberOption = option(options, "attribute:product.retention_days");
        assertThat(numberOption.operators()).contains(ComparisonOperator.GT);

        FilterOptionTarget singleText = option(options, "attribute:product.classification");
        assertThat(singleText.operators()).doesNotContain(ComparisonOperator.ALL_OF);
        assertThat(singleText.operators()).doesNotContain(ComparisonOperator.LT);
    }

    @Test
    void attributeLabel_fallsBackToTheNameAndTypeMapsUnknownToText() {
        ProductSearchContract contract = ProductSearchContract.open();
        FilterAttributeDefinition noDisplayName =
                new FilterAttributeDefinition(FilterScope.PRODUCT, "obscure_attribute", null, "WIDGET", false, false);
        FilterAttributeDefinition blankDisplayName =
                new FilterAttributeDefinition(FilterScope.PRODUCT, "blank_display", "   ", "STRING", false, false);

        List<FilterOptionTarget> options =
                FilterOptionTargets.permitted(contract, List.of(noDisplayName, blankDisplayName), "ENV");

        FilterOptionTarget obscure = option(options, "attribute:product.obscure_attribute");
        assertThat(obscure.label()).isEqualTo("obscure_attribute");
        assertThat(obscure.dataType()).isEqualTo("TEXT");

        FilterOptionTarget blank = option(options, "attribute:product.blank_display");
        assertThat(blank.label()).isEqualTo("blank_display");
    }

    @Test
    void attributesAreOrderedByLabelWithinScope_productBeforeOrganisation() {
        ProductSearchContract contract = ProductSearchContract.open();
        FilterAttributeDefinition productB =
                new FilterAttributeDefinition(FilterScope.PRODUCT, "b_attr", "Beta", "STRING", false, false);
        FilterAttributeDefinition productA =
                new FilterAttributeDefinition(FilterScope.PRODUCT, "a_attr", "alpha", "STRING", false, false);
        FilterAttributeDefinition orgB =
                new FilterAttributeDefinition(FilterScope.ORGANISATION, "org_b", "Zulu", "STRING", false, false);
        FilterAttributeDefinition orgA =
                new FilterAttributeDefinition(FilterScope.ORGANISATION, "org_a", "Yankee", "STRING", false, false);

        List<FilterOptionTarget> options =
                FilterOptionTargets.permitted(contract, List.of(productB, productA, orgB, orgA), "ENV");

        assertThat(options)
                .filteredOn(option -> option.field() == null)
                .extracting(FilterOptionTarget::key)
                .containsExactly(
                        "attribute:product.a_attr",
                        "attribute:product.b_attr",
                        "attribute:organisation.org_a",
                        "attribute:organisation.org_b");
    }

    private static FilterOptionTarget option(List<FilterOptionTarget> options, String key) {
        return options.stream()
                .filter(option -> option.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No option with key " + key));
    }

    private ProductSearchContract enforcing(String detailsJson) throws Exception {
        ProductDiscoveryPolicyDecisionDetails details =
                objectMapper.readValue(detailsJson, ProductDiscoveryPolicyDecisionDetails.class);
        return ProductSearchContract.enforcing(new PolicyDecision<>(true, List.of(), PolicyProvenance.NONE, details));
    }
}
