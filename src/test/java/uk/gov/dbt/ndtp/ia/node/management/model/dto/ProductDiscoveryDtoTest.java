/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductDiscoveryDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void requestDTO_emptyObject_deserializesWithNoViolations() throws Exception {
        ProductDiscoveryRequestDTO dto = objectMapper.readValue("{}", ProductDiscoveryRequestDTO.class);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<ConstraintViolation<ProductDiscoveryRequestDTO>> violations = validator.validate(dto);
            assertThat(violations).isEmpty();
        }
        assertThat(dto.name()).isNull();
        assertThat(dto.topic()).isNull();
        assertThat(dto.type()).isNull();
    }

    @Test
    void requestDTO_oversizedField_failsValidation() {
        ProductDiscoveryRequestDTO dto =
                ProductDiscoveryRequestDTO.builder().name("x".repeat(51)).build();

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<ConstraintViolation<ProductDiscoveryRequestDTO>> violations = validator.validate(dto);
            assertThat(violations).isNotEmpty();
        }
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
