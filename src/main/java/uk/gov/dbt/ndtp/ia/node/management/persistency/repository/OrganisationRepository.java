/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Organisation;

/**
 * Repository interface for managing {@link Organisation} entities.
 *
 * This interface provides CRUD operations and query methods for interacting with
 * the underlying database layer as it extends the {@link JpaRepository} interface.
 * It facilitates persistence and retrieval of Organisation data from the related
 * database table.
 *
 * Primary focus is on the {@link Organisation} entity with the identifier type {@link Long}.
 */
@Repository
public interface OrganisationRepository extends JpaRepository<Organisation, Long> {

    /**
     * Finds an organisation by its unique key (e.g. {@code ENV}).
     *
     * @param organisationKey the organisation key to look up
     * @return the matching organisation, or empty when no organisation carries that key
     */
    Optional<Organisation> findByOrganisationKey(String organisationKey);

    /**
     * Whether any organisation already carries the given key. The column is unique, so this is
     * the cheap way to check before writing rather than catching a constraint violation.
     *
     * @param organisationKey the organisation key to check
     * @return true when the key is already taken
     */
    boolean existsByOrganisationKey(String organisationKey);
}
