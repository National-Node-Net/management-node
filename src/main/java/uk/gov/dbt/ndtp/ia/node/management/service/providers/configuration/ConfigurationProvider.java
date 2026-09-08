/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.configuration;

import java.util.Optional;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ConsumerConfigDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ProducerConfigDTO;

/**
 * Interface for retrieving organization configuration information for both consumers and producers.
 * <p>
 * This provider interface defines methods to access configuration settings for organizations
 * based on their client identifiers. It serves as a central point for retrieving configuration
 * data that may be stored in various backend systems or repositories.
 * </p>
 *
 * @since 1.0
 */
public interface ConfigurationProvider {

    /**
     * Retrieves the configuration for a consumer organization identified by the given client ID.
     *
     * @param clientId   The unique identifier for the consumer organization. Must not be null or blank.
     * @param consumerId An optional identifier for the consumer. This can provide further specificity to the request.
     * @return The configuration settings for the specified consumer organization.
     * @throws IllegalArgumentException if the clientId is null or empty.
     * @throws RuntimeException         if the configuration cannot be retrieved due to system errors.
     */
    ConsumerConfigDTO getConsumerConfigByClientId(String clientId, Optional<Long> consumerId);

    /**
     * Retrieves the configuration for a producer organization identified by the given client ID.
     *
     * @param clientId   The unique identifier for the producer organization. Must not be null or blank.
     * @param producerId An optional identifier for the producer. This can provide further specificity to the request.
     * @return The configuration settings for the specified producer organization.
     * @throws IllegalArgumentException if the clientId is null or empty.
     * @throws RuntimeException         if the configuration cannot be retrieved due to system errors.
     */
    ProducerConfigDTO getProducerConfigByClientId(String clientId, Optional<Long> producerId);
}
