/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.dbt.ndtp.ia.node.management.converter.impl.ConsumerConverter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ConsumerDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Consumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.ConsumerRepository;

@ExtendWith(MockitoExtension.class)
class ConsumerServiceImplTest {

    @Mock
    private ConsumerRepository consumerRepository;

    @Mock
    private ConsumerConverter consumerConverter;

    @InjectMocks
    private ConsumerServiceImpl consumerService;

    private Consumer consumer;
    private ConsumerDTO consumerDTO;
    private final Long consumerId = 1L;
    private final String idpClientId = "test-client-id";

    @BeforeEach
    void setUp() {
        // Set up test data
        consumer = new Consumer();
        consumer.setId(consumerId);
        consumer.setIdpClientId(idpClientId);
        consumer.setName("Test Consumer");

        consumerDTO = ConsumerDTO.builder()
                .id(consumerId)
                .idpClientId(idpClientId)
                .name("Test Consumer")
                .build();
    }

    @Test
    void findById_withExistingId_shouldReturnConsumerDTO() {
        // Arrange
        when(consumerRepository.findById(consumerId)).thenReturn(Optional.of(consumer));
        when(consumerConverter.toDto(consumer)).thenReturn(consumerDTO);

        // Act
        Optional<ConsumerDTO> result = consumerService.findById(consumerId);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(consumerId, result.get().getId());
        assertEquals(idpClientId, result.get().getIdpClientId());
        assertEquals("Test Consumer", result.get().getName());

        // Verify
        verify(consumerRepository).findById(consumerId);
        verify(consumerConverter).toDto(consumer);
    }

    @Test
    void findById_withNonExistingId_shouldReturnEmptyOptional() {
        // Arrange
        when(consumerRepository.findById(consumerId)).thenReturn(Optional.empty());

        // Act
        Optional<ConsumerDTO> result = consumerService.findById(consumerId);

        // Assert
        assertFalse(result.isPresent());

        // Verify
        verify(consumerRepository).findById(consumerId);
        verify(consumerConverter, never()).toDto(any());
    }

    @Test
    void findByIdpClientId_withExistingClientId_shouldReturnListOfConsumerDTOs() {
        // Arrange
        List<Consumer> consumers = List.of(consumer);
        List<ConsumerDTO> consumerDTOs = List.of(consumerDTO);

        when(consumerRepository.findByIdpClientId(idpClientId)).thenReturn(consumers);
        when(consumerConverter.toDtoList(consumers)).thenReturn(consumerDTOs);

        // Act
        List<ConsumerDTO> result = consumerService.findByIdpClientId(idpClientId);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(consumerId, result.get(0).getId());
        assertEquals(idpClientId, result.get(0).getIdpClientId());

        // Verify
        verify(consumerRepository).findByIdpClientId(idpClientId);
        verify(consumerConverter).toDtoList(consumers);
    }

    @Test
    void findByIdpClientId_withNonExistingClientId_shouldReturnEmptyList() {
        // Arrange
        when(consumerRepository.findByIdpClientId(idpClientId)).thenReturn(Collections.emptyList());
        when(consumerConverter.toDtoList(Collections.emptyList())).thenReturn(Collections.emptyList());

        // Act
        List<ConsumerDTO> result = consumerService.findByIdpClientId(idpClientId);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());

        // Verify
        verify(consumerRepository).findByIdpClientId(idpClientId);
        verify(consumerConverter).toDtoList(Collections.emptyList());
    }

    @Test
    void getConsumersOfProviders_withValidProviderIds_shouldReturnMappedConsumers() {
        // Arrange
        List<Long> providerIds = List.of(1L, 2L);
        List<Consumer> consumers = List.of(consumer);

        when(consumerRepository.findConsumersByProviderIds(providerIds)).thenReturn(consumers);
        when(consumerConverter.toDto(consumer)).thenReturn(consumerDTO);

        // Act
        Map<String, List<ConsumerDTO>> result = consumerService.getConsumersOfProviders(providerIds);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.containsKey(idpClientId));
        assertEquals(1, result.get(idpClientId).size());
        assertEquals(consumerId, result.get(idpClientId).get(0).getId());

        // Verify
        verify(consumerRepository).findConsumersByProviderIds(providerIds);
        verify(consumerConverter).toDto(consumer);
    }

    @Test
    void getConsumersOfProviders_withEmptyProviderIds_shouldReturnEmptyMap() {
        // Arrange
        List<Long> emptyProviderIds = Collections.emptyList();
        when(consumerRepository.findConsumersByProviderIds(emptyProviderIds)).thenReturn(Collections.emptyList());

        // Act
        Map<String, List<ConsumerDTO>> result = consumerService.getConsumersOfProviders(emptyProviderIds);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());

        // Verify
        verify(consumerRepository).findConsumersByProviderIds(emptyProviderIds);
    }
}
