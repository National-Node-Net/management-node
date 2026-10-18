/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.policy;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeDefinitionScope;

/**
 * Repository interface for managing {@link PolicyAttributeDefinitionScope} entities.
 * Provides persistence operations and query methods for interacting with the
 * underlying database.
 *
 * Extends {@link JpaRepository} to inherit standard CRUD operations and adds
 * query methods specific to {@link PolicyAttributeDefinitionScope}.
 */
@Repository
public interface PolicyAttributeDefinitionScopeRepository extends JpaRepository<PolicyAttributeDefinitionScope, Long> {

    List<PolicyAttributeDefinitionScope> findByAttributeDefinitionId(Long attributeDefinitionId);
}
