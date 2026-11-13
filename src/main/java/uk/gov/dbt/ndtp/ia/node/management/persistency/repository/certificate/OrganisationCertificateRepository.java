/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.certificate;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.OrganisationCertificate;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.RevocationReason;

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

    /**
     * @param serialNumber the serial as {@code serial_number} stores it (see {@code CertificateSerials#toStored})
     * @return the certificate record holding that serial, with its organisation
     */
    @Query("SELECT oc FROM OrganisationCertificate oc "
            + "JOIN FETCH oc.organisation "
            + "WHERE oc.serialNumber = :serialNumber")
    Optional<OrganisationCertificate> findBySerialNumber(@Param("serialNumber") String serialNumber);

    /**
     * The reason recorded for a revoked serial, for OCSP responses.
     *
     * @param serialNumber the serial as {@code serial_number} stores it
     * @return the reason, or empty when the serial is unknown, not revoked or was revoked without a record here
     */
    @Query("SELECT oc.revocationReason FROM OrganisationCertificate oc "
            + "WHERE oc.serialNumber = :serialNumber AND oc.revokedAt IS NOT NULL")
    Optional<RevocationReason> findRevocationReasonBySerialNumber(@Param("serialNumber") String serialNumber);

    /**
     * Records a revocation, only if the record still holds that serial and is not revoked yet, so a concurrent
     * revocation or re-issue is never overwritten.
     *
     * @return the number of records updated: 1, or 0 when the condition no longer held
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE OrganisationCertificate oc SET "
            + "oc.revokedAt = :revokedAt, "
            + "oc.revokedBy = :revokedBy, "
            + "oc.revocationReason = :reason, "
            + "oc.revocationMessage = :message "
            + "WHERE oc.id = :id AND oc.serialNumber = :serialNumber AND oc.revokedAt IS NULL")
    int markRevoked(
            @Param("id") Long id,
            @Param("serialNumber") String serialNumber,
            @Param("revokedAt") Timestamp revokedAt,
            @Param("revokedBy") String revokedBy,
            @Param("reason") RevocationReason reason,
            @Param("message") String message);
}
