/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.converter.impl;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProductConsumerDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Consumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Product;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumerAttribute;

@ExtendWith(MockitoExtension.class)
class ProductConsumerConverterTest {

    @InjectMocks
    private ProductConsumerConverter converter;

    private ProductConsumer entity;
    private ProductConsumerDTO dto;
    private final Long consumerId = 1L;
    private final Long dataProviderId = 101L;
    private final Timestamp grantedTs = Timestamp.from(Instant.now());
    private final BigDecimal validity = new BigDecimal("365");

    @BeforeEach
    void setUp() {
        // Create test entity
        entity = new ProductConsumer();
        Consumer consumer = new Consumer();
        consumer.setId(consumerId);
        Product product = new Product();
        product.setId(dataProviderId);
        entity.setConsumer(consumer);
        entity.setProduct(product);
        entity.setGrantedTs(grantedTs);
        entity.setValidity(validity);

        // Create test DTO
        dto = new ProductConsumerDTO();
        dto.setConsumerId(consumerId);
        dto.setProductId(dataProviderId);
        dto.setGrantedTs(grantedTs);
        dto.setValidity(validity);
    }

    @Test
    void toDto_withNullEntity_shouldReturnNull() {
        // Act
        ProductConsumerDTO result = converter.toDto(null);

        // Assert
        assertNull(result);
    }

    @Test
    void toDto_withValidEntity_shouldReturnCorrectDTO() {
        // Add attributes to entity
        ProductConsumerAttribute attr = new ProductConsumerAttribute();
        attr.setName("classification");
        attr.setType("string");
        attr.setValue("public");
        List<ProductConsumerAttribute> attrs = new ArrayList<>();
        attrs.add(attr);
        entity.setProductConsumerAttributes(attrs);

        // Act
        ProductConsumerDTO result = converter.toDto(entity);

        // Assert
        assertNotNull(result);
        assertEquals(consumerId, result.getConsumerId());
        assertEquals(dataProviderId, result.getProductId());
        assertEquals(grantedTs, result.getGrantedTs());
        assertEquals(validity, result.getValidity());
        assertNotNull(result.getAttributes());
        assertEquals(1, result.getAttributes().size());
        assertEquals("classification", result.getAttributes().get(0).getName());
        assertEquals("string", result.getAttributes().get(0).getType());
        assertEquals("public", result.getAttributes().get(0).getValue());
    }

    @Test
    void toEntity_withNullDTO_shouldReturnNull() {
        // Act
        ProductConsumer result = converter.toEntity(null);

        // Assert
        assertNull(result);
    }

    @Test
    void toEntity_withValidDTO_shouldReturnCorrectEntity() {
        // Act
        ProductConsumer result = converter.toEntity(dto);

        // Assert
        assertNotNull(result);
        assertNotNull(result.getConsumer());
        assertNotNull(result.getProduct());
        assertEquals(consumerId, result.getConsumer().getId());
        assertEquals(dataProviderId, result.getProduct().getId());
        assertEquals(grantedTs, result.getGrantedTs());
        assertEquals(validity, result.getValidity());
    }
}
