/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.converter.impl;

import java.util.List;
import org.springframework.stereotype.Component;
import uk.gov.dbt.ndtp.ia.node.management.converter.EntityDtoConverter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProductConsumerAttributeDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProductConsumerDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Consumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Product;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumerAttribute;

/**
 * Converter for ConsumerAllowedDataProvider entity and ConsumerAllowedDataProviderDTO.
 */
@Component
public class ProductConsumerConverter implements EntityDtoConverter<ProductConsumer, ProductConsumerDTO> {

    /**
     * Converts a ConsumerAllowedDataProvider entity to a ConsumerAllowedDataProviderDTO.
     *
     * @param entity the entity to convert
     * @return the converted DTO
     */
    @Override
    public ProductConsumerDTO toDto(ProductConsumer entity) {
        if (entity == null) {
            return null;
        }

        ProductConsumerDTO dto = ProductConsumerDTO.builder()
                .id(entity.getId())
                .productId(entity.getProduct() != null ? entity.getProduct().getId() : null)
                .consumerId(entity.getConsumer() != null ? entity.getConsumer().getId() : null)
                .grantedTs(entity.getGrantedTs())
                .validity(entity.getValidity())
                .destination(entity.getDestination())
                .scheduleExpression(entity.getScheduleExpression())
                .scheduleType(entity.getScheduleType())
                .destination(entity.getDestination())
                .build();

        // Map attributes if available
        List<ProductConsumerAttribute> attrs = entity.getProductConsumerAttributes();
        if (attrs != null && !attrs.isEmpty()) {
            List<ProductConsumerAttributeDTO> attributes = attrs.stream()
                    .map(a -> ProductConsumerAttributeDTO.builder()
                            .name(a.getName())
                            .type(a.getType())
                            .value(a.getValue())
                            .build())
                    .toList();
            dto.getAttributes().addAll(attributes);
        }
        return dto;
    }

    /**
     * Converts a ConsumerAllowedDataProviderDTO to a ConsumerAllowedDataProvider entity.
     *
     * @param dto the DTO to convert
     * @return the converted entity
     */
    @Override
    public ProductConsumer toEntity(ProductConsumerDTO dto) {
        if (dto == null) {
            return null;
        }

        ProductConsumer entity = new ProductConsumer();

        entity.setGrantedTs(dto.getGrantedTs());
        entity.setValidity(dto.getValidity());
        entity.setDestination(dto.getDestination());
        entity.setScheduleExpression(dto.getScheduleExpression());
        entity.setScheduleType(dto.getScheduleType());
        if (dto.getProductId() != null) {
            Product product = new Product();
            product.setId(dto.getProductId());
            entity.setProduct(product);
        }

        if (dto.getConsumerId() != null) {
            Consumer consumer = new Consumer();
            consumer.setId(dto.getConsumerId());
            entity.setConsumer(consumer);
        }

        return entity;
    }
}
