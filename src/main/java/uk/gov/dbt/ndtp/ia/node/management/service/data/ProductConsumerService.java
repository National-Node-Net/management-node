/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data;

import java.util.List;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProductConsumerDTO;

/**
 * Service interface for managing ConsumerAllowedDataProvider entities.
 */
public interface ProductConsumerService {

    /**
     * Find all ConsumerAllowedDataProvider entities by consumer ID.
     *
     * @param consumerId the consumer ID
     * @return a list of ConsumerAllowedDataProviderDTO objects
     */
    List<ProductConsumerDTO> findByConsumerId(Long consumerId);

    List<ProductConsumerDTO> findByDataProviderId(Long providerId);
}
