/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.dbt.ndtp.ia.node.management.converter.impl.OrganisationCertificateConverter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OrganisationCertificateDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateEventType;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.RevocationReason;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.certificate.OrganisationCertificateRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.CertificateEventService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.OrganisationCertificateService;

@Service
public class OrganisationCertificateServiceImpl implements OrganisationCertificateService {

    private final OrganisationCertificateRepository repository;
    private final OrganisationCertificateConverter converter;
    private final CertificateEventService eventService;

    public OrganisationCertificateServiceImpl(
            OrganisationCertificateRepository repository,
            OrganisationCertificateConverter converter,
            CertificateEventService eventService) {
        this.repository = repository;
        this.converter = converter;
        this.eventService = eventService;
    }

    @Override
    public Optional<OrganisationCertificateDTO> findByOrganisationId(Long organisationId) {
        return repository.findByOrganisationId(organisationId).map(converter::toDto);
    }

    @Override
    public Optional<OrganisationCertificateDTO> findByClientId(String clientId) {
        return repository.findByClientId(clientId).map(converter::toDto);
    }

    @Override
    public List<OrganisationCertificateDTO> findAllByOrganisationIds(Collection<Long> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) {
            return List.of();
        }
        return converter.toDtoList(repository.findAllWithOrganisationByOrganisationIdIn(orgIds));
    }

    @Override
    public OrganisationCertificateDTO save(OrganisationCertificateDTO dto) {
        return converter.toDto(repository.save(converter.toEntity(dto)));
    }

    @Override
    public Optional<OrganisationCertificateDTO> findBySerialNumber(String serialNumber) {
        if (serialNumber == null || serialNumber.isBlank()) {
            return Optional.empty();
        }
        return repository.findBySerialNumber(serialNumber).map(converter::toDto);
    }

    @Override
    public Optional<RevocationReason> findRevocationReason(String serialNumber) {
        if (serialNumber == null || serialNumber.isBlank()) {
            return Optional.empty();
        }
        return repository.findRevocationReasonBySerialNumber(serialNumber);
    }

    @Override
    @Transactional
    public boolean recordRevocation(
            OrganisationCertificateDTO certificate,
            Instant revokedAt,
            String revokedBy,
            RevocationReason reason,
            String message) {
        int updated = repository.markRevoked(
                certificate.getId(),
                certificate.getSerialNumber(),
                Timestamp.from(revokedAt),
                revokedBy,
                reason,
                message);
        if (updated == 0) {
            return false;
        }
        eventService.recordEvent(certificate.getId(), certificate.getType(), CertificateEventType.REVOKED, revokedBy);
        return true;
    }
}
