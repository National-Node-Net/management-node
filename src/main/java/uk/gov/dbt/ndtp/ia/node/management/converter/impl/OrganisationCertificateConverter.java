/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.converter.impl;

import org.springframework.stereotype.Component;
import uk.gov.dbt.ndtp.ia.node.management.converter.EntityDtoConverter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OrganisationCertificateDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.OrganisationCertificate;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.organisation.Organisation;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.organisation.OrganisationRepository;

/**
 * Converter for OrganisationCertificate entity and OrganisationCertificateDTO.
 */
@Component
public class OrganisationCertificateConverter
        implements EntityDtoConverter<OrganisationCertificate, OrganisationCertificateDTO> {

    private final OrganisationRepository organisationRepository;

    public OrganisationCertificateConverter(OrganisationRepository organisationRepository) {
        this.organisationRepository = organisationRepository;
    }

    @Override
    public OrganisationCertificateDTO toDto(OrganisationCertificate entity) {
        if (entity == null) {
            return null;
        }

        return OrganisationCertificateDTO.builder()
                .id(entity.getId())
                .organisationId(
                        entity.getOrganisation() != null
                                ? entity.getOrganisation().getId()
                                : null)
                .certificateAutomationEnabled(
                        entity.getOrganisation() != null
                                ? entity.getOrganisation().getCertificateAutomationEnabled()
                                : null)
                .subjectDn(entity.getSubjectDn())
                .serialNumber(entity.getSerialNumber())
                .isRenewable(entity.getIsRenewable())
                .renewalTtl(entity.getRenewalTtl())
                .type(entity.getType())
                .requestedAt(entity.getRequestedAt())
                .issuedAt(entity.getIssuedAt())
                .expiresAt(entity.getExpiresAt())
                .revokedAt(entity.getRevokedAt())
                .build();
    }

    @Override
    public OrganisationCertificate toEntity(OrganisationCertificateDTO dto) {
        if (dto == null) {
            return null;
        }

        OrganisationCertificate entity = new OrganisationCertificate();
        entity.setId(dto.getId());
        entity.setSubjectDn(dto.getSubjectDn());
        entity.setSerialNumber(dto.getSerialNumber());
        entity.setIsRenewable(dto.getIsRenewable());
        entity.setRenewalTtl(dto.getRenewalTtl());
        entity.setType(dto.getType());
        entity.setRequestedAt(dto.getRequestedAt());
        entity.setIssuedAt(dto.getIssuedAt());
        entity.setExpiresAt(dto.getExpiresAt());
        entity.setRevokedAt(dto.getRevokedAt());

        if (dto.getOrganisationId() != null) {
            Organisation organisation =
                    organisationRepository.findById(dto.getOrganisationId()).orElse(null);
            entity.setOrganisation(organisation);
        }

        return entity;
    }
}
