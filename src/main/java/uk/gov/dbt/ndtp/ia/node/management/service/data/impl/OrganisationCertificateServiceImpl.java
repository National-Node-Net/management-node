/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.converter.impl.OrganisationCertificateConverter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OrganisationCertificateDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.certificate.OrganisationCertificateRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.OrganisationCertificateService;

@Service
public class OrganisationCertificateServiceImpl implements OrganisationCertificateService {

    private final OrganisationCertificateRepository repository;
    private final OrganisationCertificateConverter converter;

    public OrganisationCertificateServiceImpl(
            OrganisationCertificateRepository repository, OrganisationCertificateConverter converter) {
        this.repository = repository;
        this.converter = converter;
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
}
