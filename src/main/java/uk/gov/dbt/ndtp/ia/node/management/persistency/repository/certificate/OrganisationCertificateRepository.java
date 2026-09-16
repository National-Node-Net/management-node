/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.certificate;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.OrganisationCertificate;

/**
 * Repository interface for managing {@link OrganisationCertificate} entities.
 *
 * This interface provides CRUD operations and query methods for interacting with
 * the underlying database layer as it extends the {@link JpaRepository} interface.
 * It facilitates persistence and retrieval of OrganisationCertificate data from the related
 * database table.
 *
 * Primary focus is on the {@link OrganisationCertificate} entity with the identifier type {@link Long}.
 */
@Repository
public interface OrganisationCertificateRepository extends JpaRepository<OrganisationCertificate, Long> {

    Optional<OrganisationCertificate> findByOrganisationId(Long organisationId);

    @Query("SELECT oc FROM OrganisationCertificate oc "
            + "JOIN FETCH oc.organisation "
            + "WHERE oc.organisation.id IN :orgIds")
    List<OrganisationCertificate> findAllWithOrganisationByOrganisationIdIn(@Param("orgIds") Collection<Long> orgIds);

    @Query("SELECT oc FROM OrganisationCertificate oc "
            + "JOIN FETCH oc.organisation o "
            + "LEFT JOIN Consumer c ON c.org = o "
            + "LEFT JOIN Producer p ON p.org = o "
            + "WHERE c.idpClientId = :clientId OR p.idpClientId = :clientId")
    Optional<OrganisationCertificate> findByClientId(@Param("clientId") String clientId);
}
