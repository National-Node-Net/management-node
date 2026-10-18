/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.policy;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeValue;

/**
 * Repository interface for managing {@link PolicyAttributeValue} entities.
 * Provides persistence operations and query methods for interacting with the
 * underlying database.
 *
 * Extends {@link JpaRepository} to inherit standard CRUD operations and adds
 * query methods specific to {@link PolicyAttributeValue}.
 */
@Repository
public interface PolicyAttributeValueRepository extends JpaRepository<PolicyAttributeValue, Long> {

    List<PolicyAttributeValue> findByAttributeDefinitionScopeIdAndEntityIdAndIsDeletedFalse(
            Long attributeDefinitionScopeId, Long entityId);

    /**
     * Every live (non-soft-deleted) attribute value recorded against one entity within one
     * {@code policy_attribute_scope.code}, with its defining {@code policy_attribute_definition_scope}/
     * {@code policy_attribute_definition} eagerly fetched so callers can read {@code namespace}/
     * {@code name}/{@code data_type} without a second query per row.
     *
     * @param entityId the polymorphic entity id (e.g. a {@code producer.id} or {@code consumer.id})
     * @param scopeCode the {@code policy_attribute_scope.code} to filter to (e.g. {@code "PRODUCER"})
     */
    @Query("SELECT av FROM PolicyAttributeValue av "
            + "JOIN FETCH av.attributeDefinitionScope ads "
            + "JOIN FETCH ads.attributeDefinition ad "
            + "WHERE av.entityId = :entityId "
            + "AND ads.attributeScope.code = :scopeCode "
            + "AND av.isDeleted = false")
    List<PolicyAttributeValue> findLiveByEntityIdAndScopeCode(
            @Param("entityId") Long entityId, @Param("scopeCode") String scopeCode);
}
