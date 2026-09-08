/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Producer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Product;

/**
 * Repository interface for managing {@link Producer} entities.
 * This interface provides methods for interacting with the underlying database,
 * specifically for the {@link Producer} entity. It extends {@link JpaRepository}
 * to provide standard CRUD operations and supports custom query methods.
 * The primary focus is on enabling operations related to the {@link Producer}
 * entity with the identifier type {@link Long}.
 */
@Repository
public interface ProducerRepository extends JpaRepository<Producer, Long> {

    /**
     * Retrieves a list of {@link Producer} entities, including their associated {@link Product} entities
     * and linked product consumers, filtered by the provided list of consumer IDs.
     *
     * @param consumerIds a list of consumer IDs used to filter the {@link Producer} and associated entities
     * @return a list of {@link Producer} entities along with their associated {@link Product} entities and product consumers
     */
    @Query(" SELECT o FROM Producer o " + "JOIN FETCH o.products p  JOIN  p.productConsumer pc "
            + "JOIN FETCH p.productType t "
            + "WHERE pc.consumer.id IN :consumerIds ")
    List<Producer> findByConsumerIds(List<Long> consumerIds);

    /**
     * Retrieves a list of {@link Producer} entities, along with their associated {@link Product} entities,
     * based on the provided Identity Provider (IdP) client identifier.
     *
     * @param idpClientId the Identity Provider client identifier used to retrieve corresponding {@link Producer} entities
     * @return a list of {@link Producer} entities with their associated {@link Product} entities
     */
    @Query("SELECT o FROM Producer o " + "JOIN FETCH o.products p "
            + "JOIN FETCH p.productType t "
            + "WHERE o.idpClientId IN :idpClientId")
    List<Producer> findByIdpClientId(String idpClientId);
}
