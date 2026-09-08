/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data;

import java.util.List;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ProducerDTO;

/**
 * Service interface for managing OrganisationProducer entities.
 */
public interface ProducerService {

    /**
     * Retrieves a map of organisation IDs to lists of producer DTOs associated with those organisations.
     *
     * @param producerIds the list of producer IDs to retrieve
     * @return a map where keys are organisation IDs and values are lists of producer DTOs associated with each organisation
     */
    List<ProducerDTO> getProducersByConsumerIds(List<Long> producerIds);

    List<ProducerDTO> getProducersByClientId(String clientId);
}
