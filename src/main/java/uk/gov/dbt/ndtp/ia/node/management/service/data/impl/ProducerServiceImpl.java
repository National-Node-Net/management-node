/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import java.util.List;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.converter.impl.OrganisationProducerConverter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ProducerDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Producer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.ProducerRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProducerService;

/**
 * Implementation of the OrganisationProducerService interface.
 */
@Service
public class ProducerServiceImpl implements ProducerService {

    private final ProducerRepository producerRepository;
    private final OrganisationProducerConverter organisationProducerConverter;

    /**
     * Constructor-based dependency injection.
     *
     * @param producerRepository            the organisation producer repository
     * @param organisationProducerConverter the converter for entity-to-DTO conversion
     */
    public ProducerServiceImpl(
            ProducerRepository producerRepository, OrganisationProducerConverter organisationProducerConverter) {
        this.producerRepository = producerRepository;
        this.organisationProducerConverter = organisationProducerConverter;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ProducerDTO> getProducersByConsumerIds(List<Long> consumerIds) {
        List<Producer> producers = producerRepository.findByConsumerIds(consumerIds);

        // Convert entities to DTOs using the converter
        return organisationProducerConverter.toDtoList(producers);
    }

    @Override
    public List<ProducerDTO> getProducersByClientId(String clientId) {
        List<Producer> producers = producerRepository.findByIdpClientId(clientId);
        return organisationProducerConverter.toDtoList(producers);
    }
}
