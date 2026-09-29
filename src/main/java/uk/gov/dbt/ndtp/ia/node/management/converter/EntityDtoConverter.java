/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.converter;

import java.util.List;

/**
 * Generic interface for converting between entity and DTO objects.
 *
 * @param <E> the entity type
 * @param <D> the DTO type
 */
public interface EntityDtoConverter<E, D> {

    /**
     * Converts an entity to a DTO.
     *
     * @param entity the entity to convert
     * @return the converted DTO
     */
    D toDto(E entity);

    /**
     * Converts a DTO to an entity.
     *
     * @param dto the DTO to convert
     * @return the converted entity
     */
    E toEntity(D dto);

    /**
     * Converts a list of entities to a list of DTOs.
     *
     * @param entities the entities to convert
     * @return the converted DTOs
     */
    default List<D> toDtoList(List<E> entities) {
        if (entities == null) {
            return List.of();
        }
        return entities.stream().map(this::toDto).toList();
    }

    /**
     * Converts a list of DTOs to a list of entities.
     *
     * @param dtos the DTOs to convert
     * @return the converted entities
     */
    default List<E> toEntityList(List<D> dtos) {
        if (dtos == null) {
            return List.of();
        }
        return dtos.stream().map(this::toEntity).toList();
    }
}
