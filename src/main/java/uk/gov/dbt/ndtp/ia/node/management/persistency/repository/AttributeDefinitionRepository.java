/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeDefinition;

/**
 * Repository interface for managing {@link AttributeDefinition} entities.
 * Provides persistence operations and query methods for interacting with the
 * underlying database.
 *
 * Extends {@link JpaRepository} to inherit standard CRUD operations and adds
 * query methods specific to {@link AttributeDefinition}.
 */
@Repository
public interface AttributeDefinitionRepository extends JpaRepository<AttributeDefinition, Long> {

    Optional<AttributeDefinition> findByNamespaceAndName(String namespace, String name);
}
