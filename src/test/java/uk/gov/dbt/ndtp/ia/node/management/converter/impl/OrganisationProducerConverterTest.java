/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.converter.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProducerDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Producer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Product;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.organisation.Organisation;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.organisation.OrganisationRepository;

@ExtendWith(MockitoExtension.class)
class OrganisationProducerConverterTest {

    @Mock
    private OrganisationRepository organisationRepository;

    @Mock
    private ProductConverter productConverter;

    @InjectMocks
    private OrganisationProducerConverter converter;

    private Producer entity;
    private ProducerDTO dto;
    private Organisation organisation;
    private List<Product> dataProviders;
    private List<ProductDTO> dataProviderDTOs;

    private final Long producerId = 1L;
    private final String producerName = "Test Producer";
    private final String description = "Test Description";
    private final Boolean active = true;
    private final String host = "test-host";
    private final BigDecimal port = new BigDecimal("8080");
    private final Boolean tls = true;
    private final String idpClientId = "test-client-id";
    private final Long orgId = 101L;
    private final String orgName = "Test Organisation";

    private final Long dataProviderId1 = 201L;
    private final String dataProviderName1 = "Test Data Provider 1";
    private final String topic1 = "test-topic-1";

    private final Long dataProviderId2 = 202L;
    private final String dataProviderName2 = "Test Data Provider 2";
    private final String topic2 = "test-topic-2";

    @BeforeEach
    void setUp() {
        // Create test organisation
        organisation = new Organisation();
        organisation.setId(orgId);
        organisation.setName(orgName);

        // Create test data providers
        dataProviders = new ArrayList<>();

        Product dataProvider1 = new Product();
        dataProvider1.setId(dataProviderId1);
        dataProvider1.setName(dataProviderName1);
        dataProvider1.setTopic(topic1);

        Product dataProvider2 = new Product();
        dataProvider2.setId(dataProviderId2);
        dataProvider2.setName(dataProviderName2);
        dataProvider2.setTopic(topic2);

        dataProviders.add(dataProvider1);
        dataProviders.add(dataProvider2);

        // Create test entity
        entity = new Producer();
        entity.setId(producerId);
        entity.setName(producerName);
        entity.setDescription(description);
        entity.setActive(active);
        entity.setHost(host);
        entity.setPort(port);
        entity.setTls(tls);
        entity.setIdpClientId(idpClientId);
        entity.setOrg(organisation);
        entity.setProducts(dataProviders);

        // Set producer reference in data providers
        dataProvider1.setProducer(entity);
        dataProvider2.setProducer(entity);

        // Create test data provider DTOs
        dataProviderDTOs = new ArrayList<>();

        ProductDTO dataProviderDTO1 = ProductDTO.builder()
                .id(dataProviderId1)
                .name(dataProviderName1)
                .topic(topic1)
                .producerId(producerId)
                .build();

        ProductDTO dataProviderDTO2 = ProductDTO.builder()
                .id(dataProviderId2)
                .name(dataProviderName2)
                .topic(topic2)
                .producerId(producerId)
                .build();

        dataProviderDTOs.add(dataProviderDTO1);
        dataProviderDTOs.add(dataProviderDTO2);

        // Create test DTO
        dto = new ProducerDTO();
        dto.setId(producerId);
        dto.setName(producerName);
        dto.setDescription(description);
        dto.setActive(active);
        dto.setHost(host);
        dto.setPort(port);
        dto.setTls(tls);
        dto.setIdpClientId(idpClientId);
        dto.setOrgId(orgId);

        // Add data provider DTOs to the producer DTO
        dto.getProducts().addAll(dataProviderDTOs);

        // Set up mock behavior for productConverter
        lenient().when(productConverter.toDto(dataProvider1)).thenReturn(dataProviderDTO1);
        lenient().when(productConverter.toDto(dataProvider2)).thenReturn(dataProviderDTO2);
        lenient().when(productConverter.toEntity(dataProviderDTO1)).thenReturn(dataProvider1);
        lenient().when(productConverter.toEntity(dataProviderDTO2)).thenReturn(dataProvider2);
    }

    @Test
    void toDto_withNullEntity_shouldReturnNull() {
        // Act
        ProducerDTO result = converter.toDto(null);

        // Assert
        assertNull(result);
    }

    @Test
    void toDto_withValidEntity_shouldReturnCorrectDTO() {
        // Act
        ProducerDTO result = converter.toDto(entity);

        // Assert
        assertNotNull(result);
        assertEquals(producerId, result.getId());
        assertEquals(producerName, result.getName());
        assertEquals(description, result.getDescription());
        assertEquals(active, result.getActive());
        assertEquals(host, result.getHost());
        assertEquals(port, result.getPort());
        assertEquals(tls, result.getTls());
        assertEquals(idpClientId, result.getIdpClientId());
        assertEquals(orgId, result.getOrgId());

        // Verify dataProviders mapping
        assertNotNull(result.getProducts());
        assertEquals(2, result.getProducts().size());

        // Verify first data provider
        ProductDTO productDTO1 = result.getProducts().get(0);
        assertEquals(dataProviderId1, productDTO1.getId());
        assertEquals(dataProviderName1, productDTO1.getName());
        assertEquals(topic1, productDTO1.getTopic());
        assertEquals(producerId, productDTO1.getProducerId());

        // Verify second data provider
        ProductDTO dataProviderDTO2 = result.getProducts().get(1);
        assertEquals(dataProviderId2, dataProviderDTO2.getId());
        assertEquals(dataProviderName2, dataProviderDTO2.getName());
        assertEquals(topic2, dataProviderDTO2.getTopic());
        assertEquals(producerId, dataProviderDTO2.getProducerId());

        // Verify productConverter was called for each data provider
        verify(productConverter, times(1)).toDto(dataProviders.get(0));
        verify(productConverter, times(1)).toDto(dataProviders.get(1));
    }

    @Test
    void toDto_withNullOrg_shouldReturnDTOWithNullOrgId() {
        // Arrange
        entity.setOrg(null);

        // Act
        ProducerDTO result = converter.toDto(entity);

        // Assert
        assertNotNull(result);
        assertEquals(producerId, result.getId());
        assertEquals(producerName, result.getName());
        assertEquals(description, result.getDescription());
        assertEquals(active, result.getActive());
        assertEquals(host, result.getHost());
        assertEquals(port, result.getPort());
        assertEquals(tls, result.getTls());
        assertEquals(idpClientId, result.getIdpClientId());
        assertNull(result.getOrgId());
    }

    @Test
    void toDto_withNullProducts_shouldReturnDTOWithEmptyDataProviders() {
        // Arrange
        entity.setProducts(null);

        // Act
        ProducerDTO result = converter.toDto(entity);

        // Assert
        assertNotNull(result);
        assertNotNull(result.getProducts());
        assertTrue(result.getProducts().isEmpty());

        // Verify productConverter was not called
        verify(productConverter, never()).toDto(any());
    }

    @Test
    void toDto_withEmptyProducts_shouldReturnDTOWithEmptyDataProviders() {
        // Arrange
        entity.setProducts(new ArrayList<>());

        // Act
        ProducerDTO result = converter.toDto(entity);

        // Assert
        assertNotNull(result);
        assertNotNull(result.getProducts());
        assertTrue(result.getProducts().isEmpty());

        // Verify productConverter was not called
        verify(productConverter, never()).toDto(any());
    }

    @Test
    void toEntity_withNullDTO_shouldReturnNull() {
        // Act
        Producer result = converter.toEntity(null);

        // Assert
        assertNull(result);
    }

    @Test
    void toEntity_withValidDTO_shouldMapBasicFields() {
        // Arrange
        when(organisationRepository.findById(orgId)).thenReturn(Optional.of(organisation));

        // Act
        Producer result = converter.toEntity(dto);

        // Assert
        assertNotNull(result);
        assertEquals(producerId, result.getId());
        assertEquals(producerName, result.getName());
        assertEquals(description, result.getDescription());
        assertEquals(active, result.getActive());
        assertEquals(host, result.getHost());
        assertEquals(port, result.getPort());
        assertEquals(tls, result.getTls());
        assertEquals(idpClientId, result.getIdpClientId());
    }

    @Test
    void toEntity_withValidDTO_shouldMapOrganisation() {
        // Arrange
        when(organisationRepository.findById(orgId)).thenReturn(Optional.of(organisation));

        // Act
        Producer result = converter.toEntity(dto);

        // Assert
        assertNotNull(result.getOrg());
        assertEquals(orgId, result.getOrg().getId());
        assertEquals(orgName, result.getOrg().getName());
    }

    @Test
    void toEntity_withValidDTO_shouldMapProductsAndBackReference() {
        // Arrange
        when(organisationRepository.findById(orgId)).thenReturn(Optional.of(organisation));

        // Act
        Producer result = converter.toEntity(dto);

        // Assert
        assertNotNull(result.getProducts());
        assertEquals(2, result.getProducts().size());

        // Verify first product
        Product product1 = result.getProducts().get(0);
        assertEquals(dataProviderId1, product1.getId());
        assertEquals(dataProviderName1, product1.getName());
        assertEquals(topic1, product1.getTopic());
        assertNotNull(product1.getProducer());
        assertEquals(result, product1.getProducer());

        // Verify second product
        Product product2 = result.getProducts().get(1);
        assertEquals(dataProviderId2, product2.getId());
        assertEquals(dataProviderName2, product2.getName());
        assertEquals(topic2, product2.getTopic());
        assertNotNull(product2.getProducer());
        assertEquals(result, product2.getProducer());
    }

    @Test
    void toEntity_withValidDTO_shouldInvokeDependencies() {
        // Arrange
        when(organisationRepository.findById(orgId)).thenReturn(Optional.of(organisation));

        // Act
        converter.toEntity(dto);

        // Assert / Verify
        verify(productConverter, times(1)).toEntity(dataProviderDTOs.get(0));
        verify(productConverter, times(1)).toEntity(dataProviderDTOs.get(1));
        verify(organisationRepository, times(1)).findById(orgId);
    }

    @Test
    void toEntity_withNullOrgId_shouldReturnEntityWithNullOrg() {
        // Arrange
        dto.setOrgId(null);

        // Act
        Producer result = converter.toEntity(dto);

        // Assert
        assertNotNull(result);
        assertEquals(producerId, result.getId());
        assertEquals(producerName, result.getName());
        assertEquals(description, result.getDescription());
        assertEquals(active, result.getActive());
        assertEquals(host, result.getHost());
        assertEquals(port, result.getPort());
        assertEquals(tls, result.getTls());
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
        Producer result = converter.toEntity(dto);

        // Assert
        assertNotNull(result);
        assertEquals(producerId, result.getId());
        assertEquals(producerName, result.getName());
        assertEquals(description, result.getDescription());
        assertEquals(active, result.getActive());
        assertEquals(host, result.getHost());
        assertEquals(port, result.getPort());
        assertEquals(tls, result.getTls());
        assertEquals(idpClientId, result.getIdpClientId());
        assertNull(result.getOrg());

        // Verify
        verify(organisationRepository, times(1)).findById(orgId);
    }

    @Test
    void toEntity_withEmptyDataProviders_shouldReturnEntityWithEmptyProducts() {
        // Arrange
        dto.getProducts().clear();

        // Act
        Producer result = converter.toEntity(dto);

        // Assert
        assertNotNull(result);
        assertNull(result.getProducts());

        // Verify productConverter was not called
        verify(productConverter, never()).toEntity(any());
    }

    @Test
    void toEntity_withNullProducerId_shouldSetProducerIdInDataProviderDTO() {
        // Arrange
        when(organisationRepository.findById(orgId)).thenReturn(Optional.of(organisation));

        // Set producerId to null in data provider DTOs
        dataProviderDTOs.get(0).setProducerId(null);
        dataProviderDTOs.get(1).setProducerId(null);

        // Act
        Producer result = converter.toEntity(dto);

        // Assert
        assertNotNull(result);
        assertNotNull(result.getProducts());
        assertEquals(2, result.getProducts().size());

        // Verify producerId was set in data provider DTOs
        verify(productConverter, times(1)).toEntity(dataProviderDTOs.get(0));
        verify(productConverter, times(1)).toEntity(dataProviderDTOs.get(1));

        // Verify producerId was set in data provider DTOs
        assertEquals(producerId, dataProviderDTOs.get(0).getProducerId());
        assertEquals(producerId, dataProviderDTOs.get(1).getProducerId());
    }

    @Test
    void toEntity_withNullDataProviderFromConverter_shouldNotAddToProducts() {
        // Arrange
        when(organisationRepository.findById(orgId)).thenReturn(Optional.of(organisation));
        when(productConverter.toEntity(dataProviderDTOs.get(1))).thenReturn(null);

        // Act
        Producer result = converter.toEntity(dto);

        // Assert
        assertNotNull(result);
        assertNotNull(result.getProducts());
        assertEquals(1, result.getProducts().size());

        // Verify only one data provider was added
        assertEquals(dataProviderId1, result.getProducts().get(0).getId());
    }
}
