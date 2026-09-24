/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.ComparisonOperator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;

class ProductDiscoveryDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static Set<ConstraintViolation<ProductDiscoveryRequestDTO>> violationsOf(ProductDiscoveryRequestDTO dto) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(dto);
        }
    }

    @Test
    void requestDTO_emptyObject_deserializesWithNoViolations() throws Exception {
        ProductDiscoveryRequestDTO dto = objectMapper.readValue("{}", ProductDiscoveryRequestDTO.class);

        assertThat(violationsOf(dto)).isEmpty();
        assertThat(dto.text()).isNull();
        assertThat(dto.page()).isNull();
        assertThat(dto.size()).isNull();
        // Omitted lists read as empty rather than null, so callers need no null check.
        assertThat(dto.filters()).isNotNull().isEmpty();
        assertThat(dto.sort()).isNotNull().isEmpty();
    }

    @Test
    void requestDTO_emptyConstant_isTheRequestMadeBySendingNoBody() {
        assertThat(ProductDiscoveryRequestDTO.EMPTY.text()).isNull();
        assertThat(ProductDiscoveryRequestDTO.EMPTY.filters()).isEmpty();
        assertThat(ProductDiscoveryRequestDTO.EMPTY.sort()).isEmpty();
        assertThat(ProductDiscoveryRequestDTO.EMPTY.page()).isNull();
        assertThat(ProductDiscoveryRequestDTO.EMPTY.size()).isNull();
    }

    @Test
    void requestDTO_criteria_deserializeAsFiltersAndSortKeys() throws Exception {
        ProductDiscoveryRequestDTO dto = objectMapper.readValue(
                """
                {"text": "flood",
                 "filters": [
                   {"field": "type", "values": ["topic"]},
                   {"attribute": "record_unit", "operator": "in", "values": ["property", "geographic_area"]},
                   {"scope": "organisation", "field": "key", "operator": "eq", "values": ["ENV"]}],
                 "sort": [{"field": "name", "direction": "desc"}],
                 "page": 2,
                 "size": 20}""",
                ProductDiscoveryRequestDTO.class);

        assertThat(violationsOf(dto)).isEmpty();
        assertThat(dto.text()).isEqualTo("flood");
        assertThat(dto.page()).isEqualTo(2);
        assertThat(dto.size()).isEqualTo(20);

        assertThat(dto.filters()).hasSize(3);
        // An omitted scope and operator stay null: the defaults are chosen downstream, from the
        // target and the number of values, not baked into the DTO.
        assertThat(dto.filters().get(0).scope()).isNull();
        assertThat(dto.filters().get(0).field()).isEqualTo("type");
        assertThat(dto.filters().get(0).attribute()).isNull();
        assertThat(dto.filters().get(0).operator()).isNull();
        assertThat(dto.filters().get(0).values()).containsExactly("topic");

        assertThat(dto.filters().get(1).attribute()).isEqualTo("record_unit");
        assertThat(dto.filters().get(1).operator()).isEqualTo(ComparisonOperator.IN);
        assertThat(dto.filters().get(1).values()).containsExactly("property", "geographic_area");

        assertThat(dto.filters().get(2).scope()).isEqualTo(FilterScope.ORGANISATION);
        assertThat(dto.filters().get(2).operator()).isEqualTo(ComparisonOperator.EQ);

        assertThat(dto.sort()).hasSize(1);
        assertThat(dto.sort().get(0).field()).isEqualTo("name");
        assertThat(dto.sort().get(0).descending()).isTrue();
    }

    @Test
    void requestDTO_valuesKeepTheirJsonTypes() throws Exception {
        ProductDiscoveryRequestDTO dto = objectMapper.readValue(
                """
                {"filters": [{"attribute": "population_risk", "operator": "in", "values": [1234, 33, true]}]}""",
                ProductDiscoveryRequestDTO.class);

        assertThat(dto.filters().get(0).values()).containsExactly(1234, 33, true);
    }

    @Test
    void requestDTO_filtersAndSortAreImmutable() {
        List<ProductDiscoveryFilterDTO> filters = new ArrayList<>();
        filters.add(ProductDiscoveryFilterDTO.builder()
                .field("type")
                .values(List.of("topic"))
                .build());
        ProductDiscoveryRequestDTO dto =
                ProductDiscoveryRequestDTO.builder().filters(filters).build();

        filters.add(ProductDiscoveryFilterDTO.builder().field("added later").build());

        // The DTO copied the list, so a later change to the caller's list cannot alter the criteria.
        assertThat(dto.filters()).hasSize(1);
        var filtersView = dto.filters();
        var sortView = dto.sort();
        assertThatThrownBy(() -> filtersView.add(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> sortView.add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void filterDTO_omittedValues_readAsAnImmutableEmptyList() {
        ProductDiscoveryFilterDTO filter =
                ProductDiscoveryFilterDTO.builder().attribute("record_unit").build();

        assertThat(filter.values()).isNotNull().isEmpty();
        var valuesView = filter.values();
        assertThatThrownBy(() -> valuesView.add("v")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sortDTO_directionIsAscendingUnlessItSaysDesc() {
        assertThat(ProductDiscoverySortDTO.builder().field("name").build().descending())
                .isFalse();
        assertThat(ProductDiscoverySortDTO.builder()
                        .field("name")
                        .direction("asc")
                        .build()
                        .descending())
                .isFalse();
        assertThat(ProductDiscoverySortDTO.builder()
                        .field("name")
                        .direction("DESC")
                        .build()
                        .descending())
                .isTrue();
    }

    @Test
    void requestDTO_oversizedText_failsValidation() {
        ProductDiscoveryRequestDTO dto =
                ProductDiscoveryRequestDTO.builder().text("x".repeat(256)).build();

        assertThat(violationsOf(dto)).isNotEmpty();
    }

    @Test
    void requestDTO_tooManyFilters_failsValidation() {
        List<ProductDiscoveryFilterDTO> filters = new ArrayList<>();
        for (int i = 0; i <= 50; i++) {
            filters.add(ProductDiscoveryFilterDTO.builder()
                    .field("key" + i)
                    .values(List.of("v"))
                    .build());
        }

        assertThat(violationsOf(
                        ProductDiscoveryRequestDTO.builder().filters(filters).build()))
                .isNotEmpty();
    }

    @Test
    void requestDTO_tooManySortKeys_failsValidation() {
        List<ProductDiscoverySortDTO> sort = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            sort.add(ProductDiscoverySortDTO.builder().field("key" + i).build());
        }

        assertThat(violationsOf(ProductDiscoveryRequestDTO.builder().sort(sort).build()))
                .isNotEmpty();
    }

    @Test
    void requestDTO_oversizedFilterName_failsValidation() {
        // @Valid cascades into each filter, so an over-long field name is a 400 rather than
        // something forwarded to the PDP and the database.
        ProductDiscoveryRequestDTO dto = ProductDiscoveryRequestDTO.builder()
                .filters(List.of(ProductDiscoveryFilterDTO.builder()
                        .field("k".repeat(151))
                        .build()))
                .build();

        assertThat(violationsOf(dto)).isNotEmpty();
    }

    @Test
    void requestDTO_invalidSortDirection_failsValidation() {
        ProductDiscoveryRequestDTO dto = ProductDiscoveryRequestDTO.builder()
                .sort(List.of(ProductDiscoverySortDTO.builder()
                        .field("name")
                        .direction("sideways")
                        .build()))
                .build();

        assertThat(violationsOf(dto)).isNotEmpty();
    }

    @Test
    void requestDTO_negativePageOrZeroSize_failsValidation() {
        assertThat(violationsOf(ProductDiscoveryRequestDTO.builder().page(-1).build()))
                .isNotEmpty();
        assertThat(violationsOf(ProductDiscoveryRequestDTO.builder().size(0).build()))
                .isNotEmpty();
    }

    @Test
    void responseDTO_defaultsToEmptyList_notNull() throws Exception {
        ProductDiscoveryResponseDTO dto = ProductDiscoveryResponseDTO.builder().build();

        assertThat(dto.products()).isNotNull().isEmpty();

        String json = objectMapper.writeValueAsString(dto);
        // page and policy are absent rather than null when there is nothing to say.
        assertThat(json).contains("\"products\":[]").doesNotContain("\"page\"").doesNotContain("\"policy\"");
    }

    @Test
    void responseDTO_withProducts_serializesOnlyWhatWasSet() throws Exception {
        DiscoveredProductDTO product = DiscoveredProductDTO.builder()
                .id(99L)
                .name("Alpha")
                .topic("topic-1")
                .build();
        ProductDiscoveryResponseDTO dto = ProductDiscoveryResponseDTO.builder()
                .products(List.of(product))
                .page(ProductDiscoveryResponseDTO.Page.of(0, 20, 1, 1))
                .build();

        String json = objectMapper.writeValueAsString(dto);

        // The id is what the caller passes to the view and subscribe endpoints, so it is
        // returned; a member policy withheld is never read, so it is simply absent.
        assertThat(json)
                .contains("\"id\":99", "\"name\":\"Alpha\"", "\"topic\":\"topic-1\"")
                .doesNotContain("\"description\"", "\"producer\"");
    }

    @Test
    void responseDTO_page_countsWholePagesOfTheMatchingProducts() {
        assertThat(ProductDiscoveryResponseDTO.Page.of(0, 20, 0, 0).totalPages())
                .isZero();
        assertThat(ProductDiscoveryResponseDTO.Page.of(0, 20, 20, 20).totalPages())
                .isEqualTo(1);
        // A partial last page still counts.
        assertThat(ProductDiscoveryResponseDTO.Page.of(1, 20, 1, 21).totalPages())
                .isEqualTo(2);
    }

    /** A page that holds no rows is a misconfigured size, not a page of everything. */
    @Test
    void responseDTO_page_sizeOfZero_isNoPagesRatherThanAnArithmeticError() {
        assertThat(ProductDiscoveryResponseDTO.Page.of(0, 0, 0, 17).totalPages())
                .isZero();
    }

    /**
     * {@code size} is the page size that was applied; {@code numberOfElements} is how many products
     * came back. They part company on the last page, which is the case worth being able to tell.
     */
    @Test
    void responseDTO_page_reportsHowManyProductsThePageActuallyCarries() {
        ProductDiscoveryResponseDTO.Page full = ProductDiscoveryResponseDTO.Page.of(0, 20, 20, 21);
        ProductDiscoveryResponseDTO.Page last = ProductDiscoveryResponseDTO.Page.of(1, 20, 1, 21);

        assertThat(full.numberOfElements()).isEqualTo(20);
        assertThat(last.size()).isEqualTo(20);
        assertThat(last.numberOfElements()).isEqualTo(1);
        assertThat(last.totalElements()).isEqualTo(21);
    }

    @Test
    void responseDTO_page_isSerialisedWithBothCounts() throws Exception {
        String json = objectMapper.writeValueAsString(ProductDiscoveryResponseDTO.builder()
                .products(List.of(DiscoveredProductDTO.builder().id(1L).build()))
                .page(ProductDiscoveryResponseDTO.Page.of(2, 20, 1, 41))
                .build());

        assertThat(json)
                .contains("\"number\":2")
                .contains("\"size\":20")
                .contains("\"numberOfElements\":1")
                .contains("\"totalElements\":41")
                .contains("\"totalPages\":3");
    }

    /**
     * Grant timestamps go out as ISO-8601 strings, which is what the documented response shows and
     * what a client can parse. Jackson's default for a {@code LocalDateTime} is an array of parts;
     * the application's mapper turns that off, so this pins the wire format rather than the default.
     */
    @Test
    void discoveredProduct_timestamps_areSerialisedAsIso8601() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        LocalDateTime granted = LocalDateTime.of(2025, 7, 1, 0, 0);
        DiscoveredProductDTO product = DiscoveredProductDTO.builder()
                .id(3L)
                .name("FloodRiskMapZones")
                .subscribedBy(List.of(new DiscoveredProductDTO.SubscribingOrganisation("BCC", "Bristol", 1, granted)))
                .consumers(List.of(new DiscoveredProductDTO.Consumer(
                        "BCC-CONSUMER-1",
                        null,
                        Map.of(),
                        new DiscoveredProductDTO.Subscription(
                                granted, BigDecimal.ZERO, "cron", "0 0 6 * * *", Map.of()))))
                .build();

        String json = mapper.writeValueAsString(product);

        assertThat(json)
                .contains("\"since\":\"2025-07-01T00:00:00\"")
                .contains("\"grantedAt\":\"2025-07-01T00:00:00\"");
    }
}
