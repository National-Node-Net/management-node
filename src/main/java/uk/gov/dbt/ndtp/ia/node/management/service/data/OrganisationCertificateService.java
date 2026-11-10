/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OrganisationCertificateDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.RevocationReason;

/**
 * Service interface for managing OrganisationCertificate entities.
 */
public interface OrganisationCertificateService {

    /**
     * Find a certificate by its organisation ID.
     *
     * @param organisationId the organisation ID to search for
     * @return the certificate DTO if found
     */
    Optional<OrganisationCertificateDTO> findByOrganisationId(Long organisationId);

    /**
     * Find a certificate by the IDP client ID of its organisation's consumer or producer.
     *
     * @param clientId the IDP client ID to search for
     * @return the certificate DTO if found
     */
    Optional<OrganisationCertificateDTO> findByClientId(String clientId);

    /**
     * Find all certificates for the given organisation IDs.
     *
     * @param orgIds the organisation IDs to search for
     * @return a list of certificate DTOs
     */
    List<OrganisationCertificateDTO> findAllByOrganisationIds(Collection<Long> orgIds);

    /**
     * Save or update a certificate record.
     *
     * @param certificate the certificate DTO to save
     * @return the saved certificate DTO
     */
    OrganisationCertificateDTO save(OrganisationCertificateDTO certificate);

    /**
     * Find the certificate record holding a serial number.
     *
     * @param serialNumber the serial as {@code serial_number} stores it (see {@code CertificateSerials#toStored})
     * @return the certificate DTO if found
     */
    Optional<OrganisationCertificateDTO> findBySerialNumber(String serialNumber);

    /**
     * Find the reason recorded for a revoked serial number.
     *
     * @param serialNumber the serial as {@code serial_number} stores it
     * @return the reason, or empty when the serial is unknown, not revoked or was revoked without a reason
     */
    Optional<RevocationReason> findRevocationReason(String serialNumber);

    /**
     * Records the revocation of a certificate, and a {@code REVOKED} event, in one transaction. The record is only
     * changed while it still holds the certificate's serial and is not revoked yet.
     *
     * @param certificate the certificate record, as read before the revocation
     * @param revokedAt   the revocation time Vault returned
     * @param revokedBy   the subject of the administrator who revoked it
     * @param reason      the reason given
     * @param message     the comment given, or null
     * @return true if the revocation was recorded; false if the record was revoked or re-issued in the meantime
     */
    boolean recordRevocation(
            OrganisationCertificateDTO certificate,
            Instant revokedAt,
            String revokedBy,
            RevocationReason reason,
            String message);
}
