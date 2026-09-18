/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OrganisationCertificateDTO;

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
}
