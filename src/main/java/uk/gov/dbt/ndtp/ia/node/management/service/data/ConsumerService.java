/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ConsumerDTO;

/**
 * Service interface for managing ConsumerId entities.
 */
public interface ConsumerService {
    /**
     * Find a ConsumerId by its ID.
     *
     * @param id the IDP client ID to search for
     * @return a list of ConsumerIdDTO objects matching the ID
     */
    Optional<ConsumerDTO> findById(Long id);

    /**
     * Find an ConsumerId by its IDP client ID.
     *
     * @param idpClientId the IDP client ID to search for
     * @return a list of ConsumerIdDTO objects matching the IDP client ID
     */
    List<ConsumerDTO> findByIdpClientId(String idpClientId);

    /**
     * Retrieves a map of consumers identified by their client_id
     *
     * @param providers a list of provider IDs for which associated consumers need to be retrieved
     * @return a map where the keys are provider IDs and the values are lists of ConsumerDTO objects
     * representing the consumers associated with each provider
     */
    Map<String, List<ConsumerDTO>> getConsumersOfProviders(List<Long> providers);
}
