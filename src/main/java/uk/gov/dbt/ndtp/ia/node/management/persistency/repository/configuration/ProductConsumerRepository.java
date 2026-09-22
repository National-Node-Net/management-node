/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumer;

/**
 * Repository interface for managing and querying {@link ProductConsumer} entities in the database.
 * Extends {@link JpaRepository} for basic CRUD operations and adds custom query methods for specific use cases.
 */
@Repository
public interface ProductConsumerRepository extends JpaRepository<ProductConsumer, Long> {

    /**
     * Whether this consumer already holds a grant on this product. The pair is unique in the
     * database (uq_product_consumer_pair), so this is the check that turns a constraint violation
     * into a message the caller can act on.
     */
    boolean existsByProductIdAndConsumerId(Long productId, Long consumerId);

    /**
     * Every grant this organisation already holds on this product, across all of its consumers.
     * An organisation may subscribe one product on several consumers, so this can return more
     * than one row; it is what lets the service name the consumers already subscribed.
     */
    @Query("SELECT pc FROM ProductConsumer pc JOIN FETCH pc.consumer c "
            + "WHERE pc.product.id = :productId AND c.org.id = :orgId")
    List<ProductConsumer> findByProductIdAndOrganisationId(
            @Param("productId") Long productId, @Param("orgId") Long orgId);

    /**
     * Finds and retrieves a list of {@link ProductConsumer} entities associated with the specified consumer ID.
     * This method employs a query that fetches details of product-consumer relationships, including associated
     * consumer, product, and any attributes linked to the product-consumer relationship.
     *
     * @param consumerId the ID of the consumer whose associated product-consumer entities are to be queried
     * @return a list of {@link ProductConsumer} entities associated with the given consumer ID
     */
    @Query("Select dp from ProductConsumer  dp " + "inner join fetch dp.consumer c "
            + "inner join fetch dp.product p "
            + "left join fetch dp.productConsumerAttributes pca "
            + "where c.id=:consumerId")
    List<ProductConsumer> findByConsumerId(@Param("consumerId") Long consumerId);

    /**
     * Finds and retrieves a list of {@link ProductConsumer} entities associated with the specified product ID.
     * This method uses a query to fetch detailed information about product-consumer relationships, including
     * the associated consumer, product, and any attributes linked to the product-consumer relationship.
     *
     * @param productId the ID of the product whose associated product-consumer entities are to be queried
     * @return a list of {@link ProductConsumer} entities associated with the given product ID
     */
    @Query("Select dp from ProductConsumer  dp "
            + "inner join fetch dp.consumer consumer "
            + "inner join fetch dp.product p "
            + "left join fetch dp.productConsumerAttributes pca "
            + "where p.id=:productId")
    List<ProductConsumer> findByProductId(Long productId);
}
