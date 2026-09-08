/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.converter.impl.ConsumerConverter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ConsumerDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Consumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.ConsumerRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ConsumerService;

/**
 * Implementation of the consumerIdService interface.
 */
@Service
public class ConsumerServiceImpl implements ConsumerService {

    private final ConsumerRepository consumerRepository;
    private final ConsumerConverter consumerIdConverter;

    /**
     * Constructor-based dependency injection.
     *
     * @param consumerRepository  the organisation consumer repository
     * @param consumerIdConverter the converter for entity-to-DTO conversion
     */
    public ConsumerServiceImpl(ConsumerRepository consumerRepository, ConsumerConverter consumerIdConverter) {
        this.consumerRepository = consumerRepository;
        this.consumerIdConverter = consumerIdConverter;
    }

    @Override
    public Optional<ConsumerDTO> findById(Long id) {
        Optional<Consumer> consumer = consumerRepository.findById(id);
        return consumer.map(consumerIdConverter::toDto);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ConsumerDTO> findByIdpClientId(String idpClientId) {
        List<Consumer> consumers = consumerRepository.findByIdpClientId(idpClientId);
        return consumerIdConverter.toDtoList(consumers);
    }

    @Override
    public Map<String, List<ConsumerDTO>> getConsumersOfProviders(List<Long> providers) {
        List<Consumer> consumers = consumerRepository.findConsumersByProviderIds(providers);
        return consumers.stream()
                .map(consumerIdConverter::toDto)
                .collect(Collectors.groupingBy(
                        ConsumerDTO::getIdpClientId, Collectors.mapping(dto -> dto, Collectors.toList())));
    }
}
