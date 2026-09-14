/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import uk.gov.dbt.ndtp.ia.node.management.converter.impl.ProductConverter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Producer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Product;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.ProductRepository;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductConverter productConverter;

    private ProductServiceImpl productService;

    private Product product;
    private ProductDTO productDTO;
    private final Long productId = 1L;
    private final Long producerId = 2L;
    private final String productName = "Test Product";

    @BeforeEach
    void setUp() {
        // Constructed manually (not @InjectMocks) - the constructor's int max-candidates
        // parameter has no mock to inject
        productService = new ProductServiceImpl(productRepository, productConverter, 200);

        // Set up test data
        Producer producer = new Producer();
        producer.setId(producerId);
        producer.setName("Test Producer");

        product = new Product();
        product.setId(productId);
        product.setName(productName);
        product.setTopic("test-topic");
        product.setProducer(producer);

        productDTO = ProductDTO.builder()
                .id(productId)
                .name(productName)
                .producerId(producerId)
                .build();
    }

    @Test
    void getProductsByIds_withValidIds_shouldReturnProductDTOs() {
        // Arrange
        List<Long> productIds = List.of(productId);
        List<Product> products = List.of(product);
        List<ProductDTO> productDTOs = List.of(productDTO);

        when(productRepository.findByIds(productIds)).thenReturn(products);
        when(productConverter.toDtoList(products)).thenReturn(productDTOs);

        // Act
        List<ProductDTO> result = productService.getProductsByIds(productIds);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(productId, result.get(0).getId());
        assertEquals(productName, result.get(0).getName());
        assertEquals(producerId, result.get(0).getProducerId());

        // Verify
        verify(productRepository).findByIds(productIds);
        verify(productConverter).toDtoList(products);
    }

    @Test
    void getProductsByIds_withEmptyIds_shouldReturnEmptyList() {
        // Arrange
        List<Long> emptyIds = Collections.emptyList();

        // Act
        List<ProductDTO> result = productService.getProductsByIds(emptyIds);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());

        // Verify
        verify(productRepository, never()).findByIds(any());
    }

    @Test
    void getProductsByIds_withNullIds_shouldReturnEmptyList() {
        // Act
        List<ProductDTO> result = productService.getProductsByIds(null);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());

        // Verify
        verify(productRepository, never()).findByIds(any());
    }

    @Test
    void getProductsByIds_withNullRepositoryResult_shouldReturnEmptyList() {
        // Arrange
        List<Long> productIds = List.of(productId);
        when(productRepository.findByIds(productIds)).thenReturn(null);

        // Act
        List<ProductDTO> result = productService.getProductsByIds(productIds);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());

        // Verify
        verify(productRepository).findByIds(productIds);
        verify(productConverter, never()).toDtoList(any());
    }

    @Test
    void getProductsByProducerIds_withValidIds_shouldReturnProductDTOs() {
        // Arrange
        List<Long> producerIds = List.of(producerId);
        List<Product> products = List.of(product);
        List<ProductDTO> productDTOs = List.of(productDTO);

        when(productRepository.findByProducerIds(producerIds)).thenReturn(products);
        when(productConverter.toDtoList(products)).thenReturn(productDTOs);

        // Act
        List<ProductDTO> result = productService.getProductsByProducerIds(producerIds);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(productId, result.get(0).getId());
        assertEquals(productName, result.get(0).getName());
        assertEquals(producerId, result.get(0).getProducerId());

        // Verify
        verify(productRepository).findByProducerIds(producerIds);
        verify(productConverter).toDtoList(products);
    }

    @Test
    void getProductsByProducerIds_withEmptyIds_shouldReturnEmptyList() {
        // Arrange
        List<Long> emptyIds = Collections.emptyList();
        List<Product> emptyProducts = Collections.emptyList();
        List<ProductDTO> emptyDTOs = Collections.emptyList();

        when(productRepository.findByProducerIds(emptyIds)).thenReturn(emptyProducts);
        when(productConverter.toDtoList(emptyProducts)).thenReturn(emptyDTOs);

        // Act
        List<ProductDTO> result = productService.getProductsByProducerIds(emptyIds);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());

        // Verify
        verify(productRepository).findByProducerIds(emptyIds);
        verify(productConverter).toDtoList(emptyProducts);
    }

    @Test
    void getProductsByProducerIds_withNullRepositoryResult_shouldReturnEmptyList() {
        // Arrange
        List<Long> producerIds = List.of(producerId);
        when(productRepository.findByProducerIds(producerIds)).thenReturn(null);

        // Act
        List<ProductDTO> result = productService.getProductsByProducerIds(producerIds);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());

        // Verify
        verify(productRepository).findByProducerIds(producerIds);
        verify(productConverter, never()).toDtoList(any());
    }

    @Test
    void findDiscoveryCandidates_delegatesFiltersAndLimitToRepository() {
        // Arrange: constructed directly (not @InjectMocks) so the max-candidates limit is explicit
        ProductServiceImpl service = new ProductServiceImpl(productRepository, productConverter, 5);
        List<Product> products = List.of(product);
        List<ProductDTO> productDTOs = List.of(productDTO);

        when(productRepository.findDiscoveryCandidates(eq("Alpha"), eq("topic-1"), eq("TypeA"), any(Pageable.class)))
                .thenReturn(products);
        when(productConverter.toDtoList(products)).thenReturn(productDTOs);

        // Act
        List<ProductDTO> result = service.findDiscoveryCandidates("Alpha", "topic-1", "TypeA");

        // Assert
        assertEquals(productDTOs, result);
        verify(productRepository)
                .findDiscoveryCandidates(
                        eq("Alpha"),
                        eq("topic-1"),
                        eq("TypeA"),
                        argThat(pageable -> pageable.getPageSize() == 5 && pageable.getPageNumber() == 0));
    }

    @Test
    void findDiscoveryCandidates_blankFilters_passedAsNullToRepository() {
        // Arrange
        ProductServiceImpl service = new ProductServiceImpl(productRepository, productConverter, 5);
        when(productRepository.findDiscoveryCandidates(isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(Collections.emptyList());
        when(productConverter.toDtoList(Collections.emptyList())).thenReturn(Collections.emptyList());

        // Act
        List<ProductDTO> result = service.findDiscoveryCandidates("", null, "  ");

        // Assert
        assertTrue(result.isEmpty());
        verify(productRepository).findDiscoveryCandidates(isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void constructor_rejectsZeroMaxCandidates() {
        assertThrows(
                IllegalArgumentException.class, () -> new ProductServiceImpl(productRepository, productConverter, 0));
    }

    @Test
    void constructor_rejectsNegativeMaxCandidates() {
        assertThrows(
                IllegalArgumentException.class, () -> new ProductServiceImpl(productRepository, productConverter, -1));
    }
}
