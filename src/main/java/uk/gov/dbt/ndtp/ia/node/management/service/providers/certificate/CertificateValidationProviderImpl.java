/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OrganisationCertificateDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.data.OrganisationCertificateService;

/**
 * Implementation of {@link CertificateValidationProvider} that delegates to
 * {@link OrganisationCertificateService} for data access.
 */
@Service
public class CertificateValidationProviderImpl implements CertificateValidationProvider {

    private final OrganisationCertificateService certificateService;

    public CertificateValidationProviderImpl(OrganisationCertificateService certificateService) {
        this.certificateService = certificateService;
    }

    @Override
    public Optional<OrganisationCertificateDTO> findByClientId(String clientId) {
        return certificateService.findByClientId(clientId);
    }

    @Override
    public boolean isActive(OrganisationCertificateDTO cert) {
        return !isRevoked(cert.getRevokedAt()) && !isExpired(cert.getExpiresAt());
    }

    @Override
    public Set<Long> findActiveOrganisationIds(Collection<Long> organisationIds) {
        if (organisationIds == null || organisationIds.isEmpty()) {
            return Set.of();
        }

        List<OrganisationCertificateDTO> certs = certificateService.findAllByOrganisationIds(organisationIds);
        return certs.stream()
                .filter(this::isActive)
                .map(OrganisationCertificateDTO::getOrganisationId)
                .collect(Collectors.toSet());
    }

    private boolean isRevoked(Timestamp revokedAt) {
        return revokedAt != null && revokedAt.toInstant().isBefore(Instant.now());
    }

    private boolean isExpired(Timestamp expiresAt) {
        return expiresAt != null && expiresAt.toInstant().isBefore(Instant.now());
    }
}
