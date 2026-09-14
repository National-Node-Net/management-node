/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import uk.gov.dbt.ndtp.ia.node.management.converter.impl.ProductConverter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Product;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.ProductRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductService;

/**
 * Implementation of the OrganisationDataProviderService interface.
 */
@Service
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final ProductConverter productConverter;
    private final int maxDiscoveryCandidates;

    /**
     * Constructor-based dependency injection.
     *
     * @param productRepository the organisation data provider repository
     * @param productConverter  the converter for entity-to-DTO conversion
     * @param maxDiscoveryCandidates upper bound on candidates fetched for discovery, keeping
     *     the per-candidate PDP call loop in {@code ProductDiscoveryService} bounded
     * @throws IllegalArgumentException if maxDiscoveryCandidates is less than 1 - fails fast
     *     at startup rather than on every discovery request (PageRequest.of rejects a page
     *     size below 1)
     */
    public ProductServiceImpl(
            ProductRepository productRepository,
            ProductConverter productConverter,
            @Value("${application.product-discovery.max-candidates:200}") int maxDiscoveryCandidates) {
        if (maxDiscoveryCandidates < 1) {
            throw new IllegalArgumentException(
                    "application.product-discovery.max-candidates must be at least 1, got " + maxDiscoveryCandidates);
        }
        this.productRepository = productRepository;
        this.productConverter = productConverter;
        this.maxDiscoveryCandidates = maxDiscoveryCandidates;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ProductDTO> getProductsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Product> dataProviders = productRepository.findByIds(ids);
        return Optional.ofNullable(dataProviders)
                .map(productConverter::toDtoList)
                .orElse(List.of());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ProductDTO> getProductsByProducerIds(List<Long> producerIds) {
        List<Product> dataProviders = productRepository.findByProducerIds(producerIds);
        return Optional.ofNullable(dataProviders)
                .map(productConverter::toDtoList)
                .orElse(List.of());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ProductDTO> findDiscoveryCandidates(String name, String topic, String type) {
        Pageable limit = PageRequest.of(0, maxDiscoveryCandidates);
        List<Product> candidates = productRepository.findDiscoveryCandidates(
                blankToNull(name), blankToNull(topic), blankToNull(type), limit);
        return productConverter.toDtoList(candidates);
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
