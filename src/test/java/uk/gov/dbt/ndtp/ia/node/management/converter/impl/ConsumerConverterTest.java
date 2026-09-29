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
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ConsumerDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Consumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.organisation.Organisation;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.organisation.OrganisationRepository;

@ExtendWith(MockitoExtension.class)
class ConsumerConverterTest {

    @Mock
    private OrganisationRepository organisationRepository;

    @InjectMocks
    private ConsumerConverter converter;

    private Consumer entity;
    private ConsumerDTO dto;
    private Organisation organisation;

    private final Long consumerId = 1L;
    private final String consumerName = "Test Consumer";
    private final String idpClientId = "test-client-id";
    private final Long orgId = 101L;
    private final String orgName = "Test Organisation";

    @BeforeEach
    void setUp() {
        // Create test organisation
        organisation = new Organisation();
        organisation.setId(orgId);
        organisation.setName(orgName);

        // Create test entity
        entity = new Consumer();
        entity.setId(consumerId);
        entity.setName(consumerName);
        entity.setIdpClientId(idpClientId);
        entity.setOrg(organisation);

        // Create test DTO
        dto = new ConsumerDTO();
        dto.setId(consumerId);
        dto.setName(consumerName);
        dto.setIdpClientId(idpClientId);
        dto.setOrgId(orgId);
    }

    @Test
    void toDto_withNullEntity_shouldReturnNull() {
        // Act
        ConsumerDTO result = converter.toDto(null);

        // Assert
        assertNull(result);
    }

    @Test
    void toDto_withValidEntity_shouldReturnCorrectDTO() {
        // Act
        ConsumerDTO result = converter.toDto(entity);

        // Assert
        assertNotNull(result);
        assertEquals(consumerId, result.getId());
        assertEquals(consumerName, result.getName());
        assertEquals(idpClientId, result.getIdpClientId());
        assertEquals(orgId, result.getOrgId());
    }

    @Test
    void toDto_withNullOrg_shouldReturnDTOWithNullOrgId() {
        // Arrange
        entity.setOrg(null);

        // Act
        ConsumerDTO result = converter.toDto(entity);

        // Assert
        assertNotNull(result);
        assertEquals(consumerId, result.getId());
        assertEquals(consumerName, result.getName());
        assertEquals(idpClientId, result.getIdpClientId());
        assertNull(result.getOrgId());
    }

    @Test
    void toEntity_withNullDTO_shouldReturnNull() {
        // Act
        Consumer result = converter.toEntity(null);

        // Assert
        assertNull(result);
    }

    @Test
    void toEntity_withValidDTO_shouldReturnCorrectEntity() {
        // Arrange
        when(organisationRepository.findById(orgId)).thenReturn(Optional.of(organisation));

        // Act
        Consumer result = converter.toEntity(dto);

        // Assert
        assertNotNull(result);
        assertEquals(consumerId, result.getId());
        assertEquals(consumerName, result.getName());
        assertEquals(idpClientId, result.getIdpClientId());
        assertNotNull(result.getOrg());
        assertEquals(orgId, result.getOrg().getId());
        assertEquals(orgName, result.getOrg().getName());

        // Verify
        verify(organisationRepository, times(1)).findById(orgId);
    }

    @Test
    void toEntity_withNullOrgId_shouldReturnEntityWithNullOrg() {
        // Arrange
        dto.setOrgId(null);

        // Act
        Consumer result = converter.toEntity(dto);

        // Assert
        assertNotNull(result);
        assertEquals(consumerId, result.getId());
        assertEquals(consumerName, result.getName());
        assertEquals(idpClientId, result.getIdpClientId());
        assertNull(result.getOrg());

        // Verify
        verify(organisationRepository, never()).findById(any());
    }

    @Test
    void toEntity_withNonExistentOrgId_shouldReturnEntityWithNullOrg() {
        // Arrange
        when(organisationRepository.findById(orgId)).thenReturn(Optional.empty());

        // Act
        Consumer result = converter.toEntity(dto);

        // Assert
        assertNotNull(result);
        assertEquals(consumerId, result.getId());
        assertEquals(consumerName, result.getName());
        assertEquals(idpClientId, result.getIdpClientId());
        assertNull(result.getOrg());

        // Verify
        verify(organisationRepository, times(1)).findById(orgId);
    }

    @Test
    void toDto_withAttributes_shouldPopulateAttributesFromEntity() {
        // Arrange
        uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumer pc1 =
                new uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumer();
        uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumerAttribute a1 =
                new uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumerAttribute();
        a1.setName("attr1");
        a1.setType("string");
        a1.setValue("v1");
        uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumerAttribute a2 =
                new uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumerAttribute();
        a2.setName("attr2");
        a2.setType("number");
        a2.setValue("42");
        pc1.setProductConsumerAttributes(java.util.List.of(a1, a2));

        uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumer pc2 =
                new uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumer();
        uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumerAttribute a3 =
                new uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumerAttribute();
        a3.setName("attr3");
        a3.setType("bool");
        a3.setValue("true");
        pc2.setProductConsumerAttributes(java.util.List.of(a3));

        entity.setProductConsumers(java.util.List.of(pc1, pc2));

        // Act
        ConsumerDTO result = converter.toDto(entity);

        // Assert
        assertNotNull(result);
        assertEquals(3, result.getAttributes().size());
        assertTrue(result.getAttributes().stream()
                .anyMatch(a -> a.getName().equals("attr1")
                        && a.getType().equals("string")
                        && a.getValue().equals("v1")));
        assertTrue(result.getAttributes().stream()
                .anyMatch(a -> a.getName().equals("attr2")
                        && a.getType().equals("number")
                        && a.getValue().equals("42")));
        assertTrue(result.getAttributes().stream()
                .anyMatch(a -> a.getName().equals("attr3")
                        && a.getType().equals("bool")
                        && a.getValue().equals("true")));
    }

    @Test
    void toDto_withNoAttributes_shouldHaveEmptyAttributesList() {
        // Arrange
        entity.setProductConsumers(java.util.List.of());

        // Act
        ConsumerDTO result = converter.toDto(entity);

        // Assert
        assertNotNull(result);
        assertNotNull(result.getAttributes());
        assertEquals(0, result.getAttributes().size());
    }
}
