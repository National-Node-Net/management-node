/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProductDTO;

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
        // An omitted map reads as empty rather than null, so callers need no null check.
        assertThat(dto.filters()).isNotNull().isEmpty();
    }

    @Test
    void requestDTO_textAndFilters_deserializeWithTheirJsonTypes() throws Exception {
        ProductDiscoveryRequestDTO dto = objectMapper.readValue(
                """
                {"text": "this is sample text",
                 "filters": {"key 1": "value", "key 2": [1234, 33, 222]}}""",
                ProductDiscoveryRequestDTO.class);

        assertThat(violationsOf(dto)).isEmpty();
        assertThat(dto.text()).isEqualTo("this is sample text");
        assertThat(dto.filters()).containsEntry("key 1", "value").containsEntry("key 2", List.of(1234, 33, 222));
    }

    @Test
    void requestDTO_filtersAreImmutable() {
        Map<String, Object> source = new java.util.HashMap<>();
        source.put("key 1", "value");
        ProductDiscoveryRequestDTO dto =
                ProductDiscoveryRequestDTO.builder().filters(source).build();

        source.put("key 2", "added later");

        // The DTO copied the map, so a later change to the caller's map cannot alter the criteria.
        assertThat(dto.filters()).containsOnlyKeys("key 1");
        assertThatThrownBy(() -> dto.filters().put("key 3", "v")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requestDTO_oversizedText_failsValidation() {
        ProductDiscoveryRequestDTO dto =
                ProductDiscoveryRequestDTO.builder().text("x".repeat(256)).build();

        assertThat(violationsOf(dto)).isNotEmpty();
    }

    @Test
    void requestDTO_tooManyFilters_failsValidation() {
        Map<String, Object> filters = new java.util.LinkedHashMap<>();
        for (int i = 0; i <= 50; i++) {
            filters.put("key" + i, "v");
        }

        assertThat(violationsOf(
                        ProductDiscoveryRequestDTO.builder().filters(filters).build()))
                .isNotEmpty();
    }

    @Test
    void requestDTO_oversizedFilterName_failsValidation() {
        ProductDiscoveryRequestDTO dto = ProductDiscoveryRequestDTO.builder()
                .filters(Map.of("k".repeat(151), "v"))
                .build();

        assertThat(violationsOf(dto)).isNotEmpty();
    }

    @Test
    void responseDTO_defaultsToEmptyList_notNull() throws Exception {
        ProductDiscoveryResponseDTO dto = ProductDiscoveryResponseDTO.builder().build();

        assertThat(dto.products()).isNotNull().isEmpty();

        String json = objectMapper.writeValueAsString(dto);
        assertThat(json).contains("\"products\":[]");
    }

    @Test
    void responseDTO_withProducts_serializesWithoutInternalId() throws Exception {
        ProductDTO product =
                ProductDTO.builder().id(99L).name("Alpha").topic("topic-1").build();
        ProductDiscoveryResponseDTO dto =
                ProductDiscoveryResponseDTO.builder().products(List.of(product)).build();

        String json = objectMapper.writeValueAsString(dto);

        assertThat(json).contains("\"name\":\"Alpha\"").doesNotContain("99");
    }
}
