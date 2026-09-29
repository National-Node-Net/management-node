/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.converter.impl;

import org.springframework.stereotype.Component;
import uk.gov.dbt.ndtp.ia.node.management.converter.EntityDtoConverter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Producer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Product;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProducerRepository;

/**
 * Converter for OrganisationDataProvider entity and OrganisationDataProviderDTO.
 */
@Component
public class ProductConverter implements EntityDtoConverter<Product, ProductDTO> {

    private final ProducerRepository producerRepository;

    /**
     * Constructor-based dependency injection.
     *
     * @param producerRepository the organisation producer repository
     */
    public ProductConverter(ProducerRepository producerRepository) {
        this.producerRepository = producerRepository;
    }

    /**
     * Converts an OrganisationDataProvider entity to an OrganisationDataProviderDTO.
     *
     * @param entity the entity to convert
     * @return the converted DTO
     */
    @Override
    public ProductDTO toDto(Product entity) {
        if (entity == null) {
            return null;
        }

        String typeName =
                entity.getProductType() != null ? entity.getProductType().getName() : null;
        return ProductDTO.builder()
                .id(entity.getId())
                .name(entity.getName())
                .topic(entity.getTopic())
                .description(entity.getDescription())
                .type(typeName)
                .source(entity.getSource())
                .producerId(entity.getProducer() != null ? entity.getProducer().getId() : null)
                .build();
    }

    /**
     * Converts an OrganisationDataProviderDTO to an OrganisationDataProvider entity.
     *
     * @param dto the DTO to convert
     * @return the converted entity
     */
    @Override
    public Product toEntity(ProductDTO dto) {
        if (dto == null) {
            return null;
        }

        Product entity = new Product();
        entity.setId(dto.getId());
        entity.setName(dto.getName());
        entity.setTopic(dto.getTopic());
        entity.setDescription(dto.getDescription());
        entity.setSource(dto.getSource());

        // Set the producer if producerId is provided
        if (dto.getProducerId() != null) {
            Producer producer = producerRepository.findById(dto.getProducerId()).orElse(null);
            entity.setProducer(producer);
        }

        return entity;
    }
}
