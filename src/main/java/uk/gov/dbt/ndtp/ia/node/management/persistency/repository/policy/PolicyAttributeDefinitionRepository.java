/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.policy;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeDefinition;

/**
 * Repository interface for managing {@link PolicyAttributeDefinition} entities.
 * Provides persistence operations and query methods for interacting with the
 * underlying database.
 *
 * Extends {@link JpaRepository} to inherit standard CRUD operations and adds
 * query methods specific to {@link PolicyAttributeDefinition}.
 */
@Repository
public interface PolicyAttributeDefinitionRepository extends JpaRepository<PolicyAttributeDefinition, Long> {

    Optional<PolicyAttributeDefinition> findByNamespaceAndName(String namespace, String name);
}
