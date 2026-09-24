/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Product;

/**
 * Repository interface for managing {@link Product} entities.
 *
 * Plain CRUD over {@link Product}. Reading products for the discover and view endpoints does not
 * come through here: those build their statement from a policy decision and run it through
 * {@code ProductDiscoveryRepository}, because loading an entity would read every column - including
 * the ones policy withholds.
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {}
