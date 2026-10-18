/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import java.util.List;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.converter.impl.ProductConsumerConverter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProductConsumerDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductConsumerRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductConsumerService;

/**
 * Implementation of the ConsumerAllowedDataProviderService interface.
 */
@Service
public class ProductConsumerServiceImpl implements ProductConsumerService {

    private final ProductConsumerRepository productConsumerRepository;
    private final ProductConsumerConverter consumerProviderConverter;

    /**
     * Constructor-based dependency injection.
     *
     * @param productConsumerRepository the consumer allowed data provider repository
     * @param productConsumerConverter   the converter for entity-to-DTO conversion
     */
    public ProductConsumerServiceImpl(
            ProductConsumerRepository productConsumerRepository, ProductConsumerConverter productConsumerConverter) {
        this.productConsumerRepository = productConsumerRepository;
        this.consumerProviderConverter = productConsumerConverter;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ProductConsumerDTO> findByConsumerId(Long consumerId) {
        List<ProductConsumer> entities = productConsumerRepository.findByConsumerId(consumerId);
        return consumerProviderConverter.toDtoList(entities);
    }

    @Override
    public List<ProductConsumerDTO> findByDataProviderId(Long providerId) {
        List<ProductConsumer> entities = productConsumerRepository.findByProductId(providerId);
        return consumerProviderConverter.toDtoList(entities);
    }
}
