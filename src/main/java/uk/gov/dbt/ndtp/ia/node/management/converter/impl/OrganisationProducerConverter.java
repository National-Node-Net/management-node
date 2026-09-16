/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.converter.impl;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import uk.gov.dbt.ndtp.ia.node.management.converter.EntityDtoConverter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProducerDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Producer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Product;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.organisation.Organisation;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.organisation.OrganisationRepository;

/**
 * Converter for OrganisationProducer entity and OrganisationProducerDTO.
 */
@Component
public class OrganisationProducerConverter implements EntityDtoConverter<Producer, ProducerDTO> {

    private final OrganisationRepository organisationRepository;
    private final ProductConverter productConverter;

    /**
     * Constructor-based dependency injection.
     *
     * @param organisationRepository the organisation repository
     * @param productConverter       the data provider converter
     */
    public OrganisationProducerConverter(
            OrganisationRepository organisationRepository, ProductConverter productConverter) {
        this.organisationRepository = organisationRepository;
        this.productConverter = productConverter;
    }

    /**
     * Converts an OrganisationProducer entity to an OrganisationProducerDTO.
     *
     * @param entity the entity to convert
     * @return the converted DTO
     */
    @Override
    public ProducerDTO toDto(Producer entity) {
        if (entity == null) {
            return null;
        }

        ProducerDTO dto = ProducerDTO.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .orgId(entity.getOrg() != null ? entity.getOrg().getId() : null)
                .active(entity.getActive())
                .host(entity.getHost())
                .port(entity.getPort())
                .tls(entity.getTls())
                .idpClientId(entity.getIdpClientId())
                .build();

        // Map dataProviders if they exist
        if (entity.getProducts() != null && !entity.getProducts().isEmpty()) {
            entity.getProducts().forEach(dataProvider -> dto.getProducts().add(productConverter.toDto(dataProvider)));
        }

        return dto;
    }

    /**
     * Converts an OrganisationProducerDTO to an OrganisationProducer entity.
     *
     * @param dto the DTO to convert
     * @return the converted entity
     */
    @Override
    public Producer toEntity(ProducerDTO dto) {
        if (dto == null) {
            return null;
        }

        Producer entity = new Producer();
        entity.setId(dto.getId());
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setActive(dto.getActive());
        entity.setHost(dto.getHost());
        entity.setPort(dto.getPort());
        entity.setTls(dto.getTls());
        entity.setIdpClientId(dto.getIdpClientId());

        // Set the organisation if orgId is provided
        if (dto.getOrgId() != null) {
            Organisation organisation =
                    organisationRepository.findById(dto.getOrgId()).orElse(null);
            entity.setOrg(organisation);
        }

        // Map dataProviders if they exist
        if (dto.getProducts() != null && !dto.getProducts().isEmpty()) {
            List<Product> dataProviders = new ArrayList<>();
            dto.getProducts().forEach(dataProviderDTO -> {
                // Set the producerId to ensure proper mapping
                if (dataProviderDTO.getProducerId() == null && dto.getId() != null) {
                    dataProviderDTO.setProducerId(dto.getId());
                }
                Product product = productConverter.toEntity(dataProviderDTO);
                if (product != null) {
                    product.setProducer(entity);
                    dataProviders.add(product);
                }
            });
            entity.setProducts(dataProviders);
        }

        return entity;
    }
}
