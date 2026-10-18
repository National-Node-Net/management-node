/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.converter.impl.OrganisationConverter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.organisation.OrganisationDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.organisation.Organisation;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.organisation.OrganisationRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.OrganisationService;

/**
 * Reads organisations for callers that need an organisation's name and key without holding an
 * entity - notably the configuration APIs, which run outside a transaction and so cannot follow
 * the lazy {@code producer.org}/{@code consumer.org} associations.
 */
@Service
public class OrganisationServiceImpl implements OrganisationService {

    private final OrganisationRepository organisationRepository;
    private final OrganisationConverter organisationConverter;

    public OrganisationServiceImpl(
            OrganisationRepository organisationRepository, OrganisationConverter organisationConverter) {
        this.organisationRepository = organisationRepository;
        this.organisationConverter = organisationConverter;
    }

    @Override
    public Optional<OrganisationDTO> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return organisationRepository.findById(id).map(organisationConverter::toDto);
    }

    @Override
    public Optional<OrganisationDTO> findByKey(String organisationKey) {
        if (organisationKey == null || organisationKey.isBlank()) {
            return Optional.empty();
        }
        return organisationRepository.findByOrganisationKey(organisationKey).map(organisationConverter::toDto);
    }

    @Override
    public Optional<Long> findIdByKey(String organisationKey) {
        if (organisationKey == null || organisationKey.isBlank()) {
            return Optional.empty();
        }
        return organisationRepository.findByOrganisationKey(organisationKey).map(Organisation::getId);
    }

    @Override
    public Map<Long, OrganisationDTO> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }

        Map<Long, OrganisationDTO> byId = new LinkedHashMap<>();
        for (Organisation organisation : organisationRepository.findAllById(ids)) {
            byId.put(organisation.getId(), organisationConverter.toDto(organisation));
        }
        return byId;
    }
}
