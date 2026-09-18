/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OrganisationCertificateDTO;

/**
 * Provides certificate validation logic for organisation certificates.
 */
public interface CertificateValidationProvider {

    /**
     * Find a certificate by the IDP client ID of its organisation's consumer or producer.
     *
     * @param clientId the IDP client ID
     * @return the certificate DTO if found
     */
    Optional<OrganisationCertificateDTO> findByClientId(String clientId);

    /**
     * Checks whether the given certificate is active (not revoked and not expired).
     *
     * @param cert the certificate DTO to check
     * @return true if the certificate is active
     */
    boolean isActive(OrganisationCertificateDTO cert);

    /**
     * Returns the subset of organisation IDs that have an active certificate record.
     * Organisations without a certificate record are excluded.
     *
     * @param organisationIds the organisation IDs to check
     * @return the IDs of organisations with active certificates
     */
    Set<Long> findActiveOrganisationIds(Collection<Long> organisationIds);
}
