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
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Consumer;

@Repository
public interface ConsumerRepository extends JpaRepository<Consumer, Long> {

    List<Consumer> findByIdpClientId(String clientId);

    /**
     * Retrieves a list of {@link Consumer} entities associated with the specified provider IDs.
     * The method performs a query to fetch consumers linked with products that correspond to the given provider IDs.
     *
     * @param providers a list of IDs of the providers whose associated consumers need to be retrieved
     * @return a list of {@link Consumer} entities associated with the specified provider IDs
     */
    @Query("SELECT c FROM Consumer c  JOIN fetch c.productConsumers cp "
            + "inner join fetch cp.product p "
            + "JOIN FETCH p.productType t "
            + "inner join fetch cp.consumer consumer "
            + " WHERE p.id  IN :providers")
    List<Consumer> findConsumersByProviderIds(List<Long> providers);
}
