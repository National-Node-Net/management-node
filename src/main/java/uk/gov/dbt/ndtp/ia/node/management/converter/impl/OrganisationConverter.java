/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.converter.impl;

import org.springframework.stereotype.Component;
import uk.gov.dbt.ndtp.ia.node.management.converter.EntityDtoConverter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.OrganisationDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Organisation;

/**
 * Converter for {@link Organisation} entity and {@link OrganisationDTO}.
 *
 * <p>Policy attributes are not mapped here: they live in a separate schema reached through
 * {@code PolicyAttributeService}, so the caller assembling the response attaches them.
 */
@Component
public class OrganisationConverter implements EntityDtoConverter<Organisation, OrganisationDTO> {

    @Override
    public OrganisationDTO toDto(Organisation entity) {
        if (entity == null) {
            return null;
        }

        return OrganisationDTO.builder()
                .name(entity.getName())
                .key(entity.getOrganisationKey())
                .build();
    }

    @Override
    public Organisation toEntity(OrganisationDTO dto) {
        if (dto == null) {
            return null;
        }

        Organisation entity = new Organisation();
        entity.setName(dto.getName());
        entity.setOrganisationKey(dto.getKey());
        return entity;
    }
}
