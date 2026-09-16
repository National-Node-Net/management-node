/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.dbt.ndtp.ia.node.management.converter.impl.ProductConsumerConverter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProductConsumerDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Consumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Product;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductConsumerRepository;

@ExtendWith(MockitoExtension.class)
class ConsumerProviderOrganisationServiceImplTest {

    @Mock
    private ProductConsumerRepository productConsumerRepository;

    @Mock
    private ProductConsumerConverter productConsumerConverter;

    @InjectMocks
    private ProductConsumerServiceImpl consumerAllowedDataProviderService;

    private ProductConsumer entity1;
    private ProductConsumer entity2;
    private ProductConsumerDTO dto1;
    private ProductConsumerDTO dto2;
    private final Long consumerId = 1L;

    @BeforeEach
    void setUp() {
        // Create test entities
        entity1 = new ProductConsumer();
        Consumer consumer = new Consumer();
        consumer.setId(consumerId);
        Product product1 = new Product();
        product1.setId(101L);
        entity1.setConsumer(consumer);
        entity1.setProduct(product1);
        entity1.setGrantedTs(Timestamp.from(Instant.now()));
        entity1.setValidity(new BigDecimal("365"));

        entity2 = new ProductConsumer();
        Product product2 = new Product();
        product2.setId(102L);
        entity2.setConsumer(consumer);
        entity2.setProduct(product2);
        entity2.setGrantedTs(Timestamp.from(Instant.now()));
        entity2.setValidity(new BigDecimal("180"));

        // Create test DTOs
        dto1 = new ProductConsumerDTO();
        dto1.setConsumerId(consumerId);
        dto1.setProductId(101L);
        dto1.setGrantedTs(entity1.getGrantedTs());
        dto1.setValidity(entity1.getValidity());

        dto2 = new ProductConsumerDTO();
        dto2.setConsumerId(consumerId);
        dto2.setProductId(102L);
        dto2.setGrantedTs(entity2.getGrantedTs());
        dto2.setValidity(entity2.getValidity());
    }

    @Test
    void findByConsumerId_shouldReturnDTOList() {
        // Arrange
        List<ProductConsumer> entities = Arrays.asList(entity1, entity2);
        List<ProductConsumerDTO> dtos = Arrays.asList(dto1, dto2);
        when(productConsumerRepository.findByConsumerId(consumerId)).thenReturn(entities);
        when(productConsumerConverter.toDtoList(entities)).thenReturn(dtos);

        // Act
        List<ProductConsumerDTO> result = consumerAllowedDataProviderService.findByConsumerId(consumerId);

        // Assert
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(dto1.getConsumerId(), result.get(0).getConsumerId());
        assertEquals(dto1.getProductId(), result.get(0).getProductId());
        assertEquals(dto1.getGrantedTs(), result.get(0).getGrantedTs());
        assertEquals(dto1.getValidity(), result.get(0).getValidity());

        assertEquals(dto2.getConsumerId(), result.get(1).getConsumerId());
        assertEquals(dto2.getProductId(), result.get(1).getProductId());
        assertEquals(dto2.getGrantedTs(), result.get(1).getGrantedTs());
        assertEquals(dto2.getValidity(), result.get(1).getValidity());
    }
}
