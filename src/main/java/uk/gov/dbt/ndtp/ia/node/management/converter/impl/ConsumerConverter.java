/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.converter.impl;

import org.springframework.stereotype.Component;
import uk.gov.dbt.ndtp.ia.node.management.converter.EntityDtoConverter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ConsumerDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProductConsumerAttributeDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Consumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.organisation.Organisation;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.organisation.OrganisationRepository;

/**
 * Converter for ConsumerId entity and ConsumerIdDTO.
 */
@Component
public class ConsumerConverter implements EntityDtoConverter<Consumer, ConsumerDTO> {

    private final OrganisationRepository organisationRepository;

    /**
     * Constructor-based dependency injection.
     *
     * @param organisationRepository the organisation repository
     */
    public ConsumerConverter(OrganisationRepository organisationRepository) {
        this.organisationRepository = organisationRepository;
    }

    /**
     * Converts an ConsumerId entity to an ConsumerIdDTO.
     *
     * @param entity the entity to convert
     * @return the converted DTO
     */
    @Override
    public ConsumerDTO toDto(Consumer entity) {
        if (entity == null) {
            return null;
        }

        ConsumerDTO dto = ConsumerDTO.builder()
                .id(entity.getId())
                .name(entity.getName())
                .orgId(entity.getOrg() != null ? entity.getOrg().getId() : null)
                .idpClientId(entity.getIdpClientId())
                .scheduleExpression(entity.getScheduleExpression())
                .scheduleType(entity.getScheduleType())
                .build();

        // Populate attributes from associated ProductConsumers
        try {
            if (entity.getId() != null) {
                var productConsumers = entity.getProductConsumers();
                if (productConsumers != null) {
                    productConsumers.stream()
                            .filter(pc -> pc.getProductConsumerAttributes() != null)
                            .flatMap(pc -> pc.getProductConsumerAttributes().stream())
                            .forEach(attr -> dto.getAttributes()
                                    .add(ProductConsumerAttributeDTO.builder()
                                            .name(attr.getName())
                                            .type(attr.getType())
                                            .value(attr.getValue())
                                            .build()));
                }
            }
        } catch (Exception ignored) {
            // Keep mapping resilient
        }

        return dto;
    }

    /**
     * Converts an ConsumerIdDTO to an ConsumerId entity.
     *
     * @param dto the DTO to convert
     * @return the converted entity
     */
    @Override
    public Consumer toEntity(ConsumerDTO dto) {
        if (dto == null) {
            return null;
        }

        Consumer entity = new Consumer();
        entity.setId(dto.getId());
        entity.setName(dto.getName());
        entity.setIdpClientId(dto.getIdpClientId());
        entity.setScheduleExpression(dto.getScheduleExpression());
        entity.setScheduleType(dto.getScheduleType());
        // Set the organisation if orgId is provided
        if (dto.getOrgId() != null) {
            Organisation organisation =
                    organisationRepository.findById(dto.getOrgId()).orElse(null);
            entity.setOrg(organisation);
        }

        return entity;
    }
}
