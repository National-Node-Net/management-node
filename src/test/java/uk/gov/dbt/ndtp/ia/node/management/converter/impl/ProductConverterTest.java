/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.converter.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Producer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Product;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProducerRepository;

@ExtendWith(MockitoExtension.class)
class ProductConverterTest {

    @Mock
    private ProducerRepository producerRepository;

    @InjectMocks
    private ProductConverter converter;

    private Product entity;
    private ProductDTO dto;
    private Producer producer;

    private final Long dataProviderId = 1L;
    private final String dataProviderName = "Test Data Provider";
    private final String topic = "test-topic";
    private final String description = "Prose describing the product";
    private final Long producerId = 101L;
    private final String producerName = "Test Producer";

    @BeforeEach
    void setUp() {
        // Create test producer
        producer = new Producer();
        producer.setId(producerId);
        producer.setName(producerName);

        // Create test entity
        entity = new Product();
        entity.setId(dataProviderId);
        entity.setName(dataProviderName);
        entity.setTopic(topic);
        entity.setDescription(description);
        entity.setProducer(producer);

        // Create test DTO
        dto = new ProductDTO();
        dto.setId(dataProviderId);
        dto.setName(dataProviderName);
        dto.setTopic(topic);
        dto.setDescription(description);
        dto.setProducerId(producerId);
    }

    @Test
    void toDto_withNullEntity_shouldReturnNull() {
        // Act
        ProductDTO result = converter.toDto(null);

        // Assert
        assertNull(result);
    }

    @Test
    void toDto_withValidEntity_shouldReturnCorrectDTO() {
        // Act
        ProductDTO result = converter.toDto(entity);

        // Assert
        assertNotNull(result);
        assertEquals(dataProviderId, result.getId());
        assertEquals(dataProviderName, result.getName());
        assertEquals(topic, result.getTopic());
        assertEquals(description, result.getDescription());
        assertEquals(producerId, result.getProducerId());
    }

    @Test
    void toDto_withNullDescription_shouldReturnDTOWithNullDescription() {
        // Arrange
        entity.setDescription(null);

        // Act
        ProductDTO result = converter.toDto(entity);

        // Assert
        assertNotNull(result);
        assertNull(result.getDescription());
    }

    @Test
    void toDto_withNullProducer_shouldReturnDTOWithNullProducerId() {
        // Arrange
        entity.setProducer(null);

        // Act
        ProductDTO result = converter.toDto(entity);

        // Assert
        assertNotNull(result);
        assertEquals(dataProviderId, result.getId());
        assertEquals(dataProviderName, result.getName());
        assertEquals(topic, result.getTopic());
        assertNull(result.getProducerId());
    }

    @Test
    void toEntity_withNullDTO_shouldReturnNull() {
        // Act
        Product result = converter.toEntity(null);

        // Assert
        assertNull(result);
    }

    @Test
    void toEntity_withValidDTO_shouldReturnCorrectEntity() {
        // Arrange
        when(producerRepository.findById(producerId)).thenReturn(Optional.of(producer));

        // Act
        Product result = converter.toEntity(dto);

        // Assert
        assertNotNull(result);
        assertEquals(dataProviderId, result.getId());
        assertEquals(dataProviderName, result.getName());
        assertEquals(topic, result.getTopic());
        assertEquals(description, result.getDescription());
        assertNotNull(result.getProducer());
        assertEquals(producerId, result.getProducer().getId());
        assertEquals(producerName, result.getProducer().getName());

        // Verify
        verify(producerRepository, times(1)).findById(producerId);
    }

    @Test
    void toEntity_withNullDescription_shouldReturnEntityWithNullDescription() {
        // Arrange
        dto.setDescription(null);
        dto.setProducerId(null);

        // Act
        Product result = converter.toEntity(dto);

        // Assert
        assertNotNull(result);
        assertNull(result.getDescription());
    }

    @Test
    void description_roundTripsThroughBothConversions() {
        // Arrange
        when(producerRepository.findById(producerId)).thenReturn(Optional.of(producer));

        // Act
        ProductDTO asDto = converter.toDto(entity);
        Product asEntity = converter.toEntity(asDto);

        // Assert
        assertEquals(description, asDto.getDescription());
        assertEquals(description, asEntity.getDescription());
    }

    @Test
    void toEntity_withNullProducerId_shouldReturnEntityWithNullProducer() {
        // Arrange
        dto.setProducerId(null);

        // Act
        Product result = converter.toEntity(dto);

        // Assert
        assertNotNull(result);
        assertEquals(dataProviderId, result.getId());
        assertEquals(dataProviderName, result.getName());
        assertEquals(topic, result.getTopic());
        assertNull(result.getProducer());

        // Verify
        verify(producerRepository, never()).findById(any());
    }

    @Test
    void toEntity_withNonExistentProducerId_shouldReturnEntityWithNullProducer() {
        // Arrange
        when(producerRepository.findById(producerId)).thenReturn(Optional.empty());

        // Act
        Product result = converter.toEntity(dto);

        // Assert
        assertNotNull(result);
        assertEquals(dataProviderId, result.getId());
        assertEquals(dataProviderName, result.getName());
        assertEquals(topic, result.getTopic());
        assertNull(result.getProducer());

        // Verify
        verify(producerRepository, times(1)).findById(producerId);
    }
}
