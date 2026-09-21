/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.ia.node.management.exception.AccessRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.InvalidSearchCriteriaException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryFilterDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoveryRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductDiscoverySortDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.ComparisonOperator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterNode;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterTarget;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyProvenance;

/**
 * Covers turning a request body into criteria: the defaults, what is malformed ({@code 400}) and
 * what the contract refuses ({@code 403}, naming each refusal). A name the caller may not use is
 * refused the same way whether or not it exists, so the endpoint cannot be used to probe the
 * vocabulary.
 */
class ProductSearchCriteriaFactoryTest {

    private final ProductSearchCriteriaFactory factory = new ProductSearchCriteriaFactory();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProductSearchContract open = ProductSearchContract.open();

    @Test
    void create_nullRequest_isTheFirstPageSortedByName() {
        ProductSearchCriteria criteria = factory.create(null, open, Set.of());

        assertThat(criteria.text()).isNull();
        assertThat(criteria.hasText()).isFalse();
        assertThat(criteria.filters()).isEmpty();
        assertThat(criteria.sort()).containsExactly(ProductSearchCriteria.SortKey.byField(ProductField.NAME, false));
        assertThat(criteria.page()).isZero();
        assertThat(criteria.size()).isEqualTo(20);
    }

    @Test
    void create_emptyRequest_isTheFirstPageSortedByName() {
        ProductSearchCriteria criteria = factory.create(ProductDiscoveryRequestDTO.EMPTY, open, Set.of());

        assertThat(criteria.filters()).isEmpty();
        assertThat(criteria.sort()).containsExactly(ProductSearchCriteria.SortKey.byField(ProductField.NAME, false));
        assertThat(criteria.page()).isZero();
        assertThat(criteria.size()).isEqualTo(20);
    }

    @Test
    void create_fieldsAndAttributes_keepTheirScopeAndQualifiedName() {
        ProductDiscoveryRequestDTO request = request(List.of(
                filter(null, "topic", null, null, List.of("weather")),
                filter(FilterScope.ORGANISATION, "key", null, null, List.of("ENV")),
                filter(null, null, "identifiability", null, List.of("anonymised")),
                filter(FilterScope.ORGANISATION, null, "clearance", null, List.of("SECRET"))));

        List<FilterNode.Comparison> filters =
                factory.create(request, open, Set.of()).filters();

        assertThat(filters)
                .extracting(comparison -> comparison.target().qualifiedName())
                .containsExactly("topic", "organisation.key", "identifiability", "organisation.clearance");
        assertThat(filters)
                .extracting(comparison -> comparison.target().field())
                .containsExactly(true, true, false, false);
        assertThat(filters)
                .extracting(comparison -> comparison.target().scope())
                .containsExactly(
                        FilterScope.PRODUCT, FilterScope.ORGANISATION, FilterScope.PRODUCT, FilterScope.ORGANISATION);
        assertThat(filters.get(0).values()).containsExactly("weather");
    }

    @Test
    void create_singleValueOnATextSearchableField_defaultsToContains() {
        ProductDiscoveryRequestDTO request = request(List.of(filter(null, "name", null, null, List.of("flood"))));

        assertThat(factory.create(request, open, Set.of()).filters())
                .extracting(FilterNode.Comparison::operator)
                .containsExactly(ComparisonOperator.CONTAINS);
    }

    @Test
    void create_singleValueElsewhere_defaultsToEquals() {
        ProductDiscoveryRequestDTO request = request(List.of(
                filter(null, "topic", null, null, List.of("weather")),
                filter(null, null, "identifiability", null, List.of("anonymised"))));

        assertThat(factory.create(request, open, Set.of()).filters())
                .extracting(FilterNode.Comparison::operator)
                .containsExactly(ComparisonOperator.EQ, ComparisonOperator.EQ);
    }

    @Test
    void create_severalValues_defaultsToIn() {
        ProductDiscoveryRequestDTO request = request(List.of(
                filter(null, "name", null, null, List.of("flood", "fire")),
                filter(null, null, "identifiability", null, List.of("anonymised", "personal"))));

        assertThat(factory.create(request, open, Set.of()).filters())
                .extracting(FilterNode.Comparison::operator)
                .containsExactly(ComparisonOperator.IN, ComparisonOperator.IN);
    }

    @Test
    void create_operatorGiven_isKept() {
        ProductDiscoveryRequestDTO request = request(List.of(
                filter(null, null, "identifiability", ComparisonOperator.NOT_IN, List.of("personal")),
                filter(null, null, "record_unit", ComparisonOperator.EXISTS, List.of())));

        assertThat(factory.create(request, open, Set.of()).filters())
                .extracting(FilterNode.Comparison::operator)
                .containsExactly(ComparisonOperator.NOT_IN, ComparisonOperator.EXISTS);
    }

    @Test
    void create_sortKeys_areKeptInOrderWithTheirDirection() {
        ProductDiscoveryRequestDTO request = ProductDiscoveryRequestDTO.builder()
                .sort(List.of(
                        ProductDiscoverySortDTO.builder()
                                .field("topic")
                                .direction("desc")
                                .build(),
                        ProductDiscoverySortDTO.builder()
                                .attribute("identifiability")
                                .build()))
                .build();

        assertThat(factory.create(request, open, Set.of()).sort())
                .containsExactly(
                        ProductSearchCriteria.SortKey.byField(ProductField.TOPIC, true),
                        ProductSearchCriteria.SortKey.byAttribute(FilterScope.PRODUCT, "identifiability", false));
    }

    @Test
    void create_text_isTrimmedAndKept() {
        ProductDiscoveryRequestDTO request =
                ProductDiscoveryRequestDTO.builder().text("  flood  ").build();

        ProductSearchCriteria criteria = factory.create(request, open, Set.of());

        assertThat(criteria.text()).isEqualTo("flood");
        assertThat(criteria.hasText()).isTrue();
    }

    @Test
    void create_filterNamingBothFieldAndAttribute_isRejected() {
        ProductDiscoveryRequestDTO request =
                request(List.of(filter(null, "name", "identifiability", null, List.of("flood"))));

        assertThatThrownBy(() -> factory.create(request, open, Set.of()))
                .isInstanceOf(InvalidSearchCriteriaException.class)
                .hasMessageContaining("exactly one of 'field' and 'attribute'");
    }

    @Test
    void create_filterNamingNeitherFieldNorAttribute_isRejected() {
        ProductDiscoveryRequestDTO request = request(List.of(filter(null, null, null, null, List.of("flood"))));

        assertThatThrownBy(() -> factory.create(request, open, Set.of()))
                .isInstanceOf(InvalidSearchCriteriaException.class)
                .hasMessageContaining("exactly one of 'field' and 'attribute'");
    }

    @Test
    void create_operatorGivenTheWrongNumberOfValues_isRejected() {
        ProductDiscoveryRequestDTO request =
                request(List.of(filter(null, "topic", null, ComparisonOperator.EQ, List.of("weather", "climate"))));

        assertThatThrownBy(() -> factory.create(request, open, Set.of()))
                .isInstanceOf(InvalidSearchCriteriaException.class)
                .hasMessageContaining("cannot take 2 value(s)");
    }

    @Test
    void create_valueThatIsNotTextNumberOrBoolean_isRejected() {
        ProductDiscoveryRequestDTO request =
                request(List.of(filter(null, null, "identifiability", null, List.of(Map.of("nested", "value")))));

        assertThatThrownBy(() -> factory.create(request, open, Set.of()))
                .isInstanceOf(InvalidSearchCriteriaException.class)
                .hasMessageContaining("must be text, numbers or booleans");
    }

    @Test
    void create_valueLongerThanTheLimit_isRejected() {
        ProductDiscoveryRequestDTO request =
                request(List.of(filter(null, null, "identifiability", null, List.of("x".repeat(256)))));

        assertThatThrownBy(() -> factory.create(request, open, Set.of()))
                .isInstanceOf(InvalidSearchCriteriaException.class)
                .hasMessageContaining("longer than 255 characters");
    }

    @Test
    void create_orderingOperatorOnANonNumber_isRejected() {
        ProductDiscoveryRequestDTO request =
                request(List.of(filter(null, null, "record_count", ComparisonOperator.GT, List.of("many"))));

        assertThatThrownBy(() -> factory.create(request, open, Set.of()))
                .isInstanceOf(InvalidSearchCriteriaException.class)
                .hasMessageContaining("needs a number");
    }

    @Test
    void create_orderingOperatorOnAField_isRejected() {
        ProductDiscoveryRequestDTO request =
                request(List.of(filter(null, "topic", null, ComparisonOperator.GT, List.of(5))));

        assertThatThrownBy(() -> factory.create(request, open, Set.of()))
                .isInstanceOf(InvalidSearchCriteriaException.class)
                .hasMessageContaining("does not apply to the field 'topic'");
    }

    @Test
    void create_allOfOnAField_isRejected() {
        ProductDiscoveryRequestDTO request =
                request(List.of(filter(null, "topic", null, ComparisonOperator.ALL_OF, List.of("a", "b"))));

        assertThatThrownBy(() -> factory.create(request, open, Set.of()))
                .isInstanceOf(InvalidSearchCriteriaException.class)
                .hasMessageContaining("does not apply to the field 'topic'");
    }

    @Test
    void create_sortOnANonSortableField_isRejected() {
        ProductDiscoveryRequestDTO request = ProductDiscoveryRequestDTO.builder()
                .sort(List.of(
                        ProductDiscoverySortDTO.builder().field("description").build()))
                .build();

        assertThatThrownBy(() -> factory.create(request, open, Set.of()))
                .isInstanceOf(InvalidSearchCriteriaException.class)
                .hasMessageContaining("cannot be sorted by 'description'");
    }

    @Test
    void create_filterOnAFieldTheContractRefuses_isRefusedByName() throws JsonProcessingException {
        ProductSearchContract contract = enforcing("""
                {"allowed_filtered_fields": ["name"]}""");
        ProductDiscoveryRequestDTO request = request(List.of(
                filter(null, "topic", null, null, List.of("weather")),
                filter(FilterScope.ORGANISATION, "key", null, null, List.of("ENV"))));

        AccessRejectedException refusal = refusalOf(request, contract, Set.of());

        assertThat(refusal).hasMessage("Access denied by policy");
        assertThat(refusal.getErrorId()).isNotBlank();
        assertThat(refusal.getReasons())
                .containsExactly(
                        ProductSearchCriteriaFactory.REASON_FIELD + "topic",
                        ProductSearchCriteriaFactory.REASON_FIELD + "organisation.key");
    }

    @Test
    void create_filterOnAnAttributeTheContractRefuses_isRefusedByName() throws JsonProcessingException {
        ProductSearchContract contract =
                enforcing("""
                {"allowed_filtered_attributes": ["identifiability"]}""");
        ProductDiscoveryRequestDTO request = request(List.of(
                filter(null, null, "population_risk_tags", null, List.of("children")),
                filter(FilterScope.ORGANISATION, null, "clearance", null, List.of("SECRET"))));

        assertThat(refusalOf(request, contract, Set.of()).getReasons())
                .containsExactly(
                        ProductSearchCriteriaFactory.REASON_ATTRIBUTE + "population_risk_tags",
                        ProductSearchCriteriaFactory.REASON_ATTRIBUTE + "organisation.clearance");
    }

    @Test
    void create_sortTheContractRefuses_isRefusedByName() throws JsonProcessingException {
        ProductSearchContract contract = enforcing("""
                {"allowed_filtered_fields": ["topic"]}""");
        ProductDiscoveryRequestDTO request = ProductDiscoveryRequestDTO.builder()
                .sort(List.of(ProductDiscoverySortDTO.builder().field("name").build()))
                .build();

        assertThat(refusalOf(request, contract, Set.of()).getReasons())
                .containsExactly(ProductSearchCriteriaFactory.REASON_SORT + "name");
    }

    @Test
    void create_textWithoutAPermittedTextField_isRefused() throws JsonProcessingException {
        ProductSearchContract contract = enforcing("""
                {"visible_fields": ["name"]}""");
        ProductDiscoveryRequestDTO request =
                ProductDiscoveryRequestDTO.builder().text("flood").build();

        assertThat(refusalOf(request, contract, Set.of()).getReasons())
                .containsExactly(ProductSearchCriteriaFactory.REASON_TEXT);
    }

    @Test
    void create_severalRefusals_accumulateIntoOneException() throws JsonProcessingException {
        ProductSearchContract contract = enforcing("{}");
        ProductDiscoveryRequestDTO request = ProductDiscoveryRequestDTO.builder()
                .text("flood")
                .filters(List.of(
                        filter(null, "topic", null, null, List.of("weather")),
                        filter(null, null, "identifiability", null, List.of("anonymised"))))
                .sort(List.of(ProductDiscoverySortDTO.builder().field("name").build()))
                .build();

        assertThat(refusalOf(request, contract, Set.of()).getReasons())
                .containsExactly(
                        ProductSearchCriteriaFactory.REASON_FIELD + "topic",
                        ProductSearchCriteriaFactory.REASON_ATTRIBUTE + "identifiability",
                        ProductSearchCriteriaFactory.REASON_SORT + "name",
                        ProductSearchCriteriaFactory.REASON_TEXT);
    }

    @Test
    void create_sensitiveAttribute_isRefusedWhenTheContractMasksSensitiveAttributes() throws JsonProcessingException {
        ProductSearchContract contract =
                enforcing("""
                {"allowed_filtered_attributes": ["record_unit"]}""");
        ProductDiscoveryRequestDTO request =
                request(List.of(filter(null, null, "record_unit", null, List.of("property"))));

        assertThat(contract.masksSensitiveAttributes()).isTrue();
        assertThat(refusalOf(request, contract, Set.of("record_unit")).getReasons())
                .containsExactly(ProductSearchCriteriaFactory.REASON_ATTRIBUTE + "record_unit");
    }

    @Test
    void create_sensitiveAttribute_isAllowedWhenTheContractDoesNotMaskThem() throws JsonProcessingException {
        ProductSearchContract contract = enforcing(
                """
                {"allowed_filtered_attributes": ["record_unit"], "mask_sensitive_attributes": false}""");
        ProductDiscoveryRequestDTO request =
                request(List.of(filter(null, null, "record_unit", null, List.of("property"))));

        assertThat(factory.create(request, contract, Set.of("record_unit")).filters())
                .extracting(comparison -> comparison.target().qualifiedName())
                .containsExactly("record_unit");
    }

    @Test
    void create_sizeAboveTheContractsMaximum_isClampedNotRefused() throws JsonProcessingException {
        ProductSearchContract contract = enforcing("{\"max_page_size\": 25}");
        ProductDiscoveryRequestDTO request =
                ProductDiscoveryRequestDTO.builder().page(3).size(500).build();

        ProductSearchCriteria criteria = factory.create(request, contract, Set.of());

        assertThat(criteria.size()).isEqualTo(25);
        assertThat(criteria.page()).isEqualTo(3);
        assertThat(criteria.offset()).isEqualTo(75);
    }

    @Test
    void create_unknownFilterName_isRefusedLikeAForbiddenOne() throws JsonProcessingException {
        ProductSearchContract contract = enforcing("""
                {"allowed_filtered_fields": ["name"]}""");
        ProductDiscoveryRequestDTO request =
                request(List.of(filter(null, "no_such_field", null, null, List.of("anything"))));

        assertThat(refusalOf(request, contract, Set.of()).getReasons())
                .containsExactly(ProductSearchCriteriaFactory.REASON_FIELD + "no_such_field");
    }

    @Test
    void create_unknownFilterNameWithoutPolicy_isMalformedCriteria() {
        ProductDiscoveryRequestDTO request =
                request(List.of(filter(null, "no_such_field", null, null, List.of("anything"))));

        assertThatThrownBy(() -> factory.create(request, open, Set.of()))
                .isInstanceOf(InvalidSearchCriteriaException.class)
                .hasMessageContaining("cannot be filtered on 'no_such_field'");
    }

    @Test
    void create_permittedTargets_areNotRefused() throws JsonProcessingException {
        ProductSearchContract contract = enforcing(
                """
                {"allowed_filtered_fields": ["name"], "allowed_filtered_attributes": ["identifiability"],
                 "visible_fields": ["name"], "text_search_fields": ["name"]}""");
        ProductDiscoveryRequestDTO request = ProductDiscoveryRequestDTO.builder()
                .text("flood")
                .filters(List.of(
                        filter(null, "name", null, null, List.of("flood")),
                        filter(null, null, "identifiability", null, List.of("anonymised"))))
                .sort(List.of(ProductDiscoverySortDTO.builder().field("name").build()))
                .build();

        ProductSearchCriteria criteria = factory.create(request, contract, Set.of());

        assertThat(criteria.text()).isEqualTo("flood");
        assertThat(criteria.filters())
                .extracting(comparison -> comparison.target())
                .containsExactly(FilterTarget.ofField("name"), FilterTarget.ofAttribute("identifiability"));
        assertThat(criteria.sort()).containsExactly(ProductSearchCriteria.SortKey.byField(ProductField.NAME, false));
    }

    @Test
    void create_qualifiedName_meansTheSameAsAnExplicitScope() {
        ProductDiscoveryRequestDTO request = request(List.of(
                filter(null, "organisation.name", null, null, List.of("Homes England")),
                filter(null, null, "organisation.jurisdictions", ComparisonOperator.ANY_OF, List.of("Bristol"))));

        List<FilterNode.Comparison> filters =
                factory.create(request, open, Set.of()).filters();

        assertThat(filters)
                .extracting(FilterNode.Comparison::target)
                .containsExactly(
                        new FilterTarget(FilterScope.ORGANISATION, true, "name"),
                        new FilterTarget(FilterScope.ORGANISATION, false, "jurisdictions"));
        assertThat(filters)
                .extracting(comparison -> comparison.target().qualifiedName())
                .containsExactly("organisation.name", "organisation.jurisdictions");
    }

    @Test
    void create_qualifiedSortKey_meansTheSameAsAnExplicitScope() {
        ProductDiscoveryRequestDTO request = ProductDiscoveryRequestDTO.builder()
                .sort(List.of(ProductDiscoverySortDTO.builder()
                        .field("organisation.name")
                        .build()))
                .build();

        assertThat(factory.create(request, open, Set.of()).sort())
                .containsExactly(ProductSearchCriteria.SortKey.byField(ProductField.ORGANISATION_NAME, false));
    }

    @Test
    void create_qualifiedAttribute_isPermittedAndRefusedByTheNameThePolicyLists() throws JsonProcessingException {
        ProductSearchContract contract = enforcing(
                """
                {"allowed_filtered_attributes": ["organisation.jurisdictions"],
                 "visible_fields": ["name"], "text_search_fields": ["name"]}""");

        assertThat(factory.create(
                                request(List.of(filter(
                                        null,
                                        null,
                                        "organisation.jurisdictions",
                                        ComparisonOperator.ANY_OF,
                                        List.of("Bristol")))),
                                contract,
                                Set.of())
                        .filters())
                .extracting(comparison -> comparison.target().scope())
                .containsExactly(FilterScope.ORGANISATION);

        assertThat(refusalOf(
                                request(List.of(
                                        filter(null, null, "organisation.responsibility_areas", null, List.of("x")))),
                                contract,
                                Set.of())
                        .getReasons())
                .containsExactly("filter.attribute_not_permitted:organisation.responsibility_areas");
    }

    /** The refusal the factory raised, so a test can assert every reason it names. */
    private AccessRejectedException refusalOf(
            ProductDiscoveryRequestDTO request, ProductSearchContract contract, Set<String> sensitiveAttributes) {
        try {
            factory.create(request, contract, sensitiveAttributes);
        } catch (AccessRejectedException refusal) {
            return refusal;
        }
        throw new AssertionError("Expected the criteria to be refused by the contract");
    }

    private ProductSearchContract enforcing(String detailsJson) throws JsonProcessingException {
        ProductDiscoveryPolicyDecisionDetails details =
                objectMapper.readValue(detailsJson, ProductDiscoveryPolicyDecisionDetails.class);
        return ProductSearchContract.enforcing(new PolicyDecision<>(true, List.of(), PolicyProvenance.NONE, details));
    }

    private static ProductDiscoveryRequestDTO request(List<ProductDiscoveryFilterDTO> filters) {
        return ProductDiscoveryRequestDTO.builder().filters(filters).build();
    }

    private static ProductDiscoveryFilterDTO filter(
            FilterScope scope, String field, String attribute, ComparisonOperator operator, List<Object> values) {
        return ProductDiscoveryFilterDTO.builder()
                .scope(scope)
                .field(field)
                .attribute(attribute)
                .operator(operator)
                .values(values)
                .build();
    }
}
