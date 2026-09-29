/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EntityDtoConverterTest {

    private final EntityDtoConverter<String, Integer> stubConverter = new EntityDtoConverter<>() {
        @Override
        public Integer toDto(String entity) {
            return entity == null ? null : Integer.parseInt(entity);
        }

        @Override
        public String toEntity(Integer dto) {
            return dto == null ? null : String.valueOf(dto);
        }
    };

    @Test
    void testToDtoList() {
        // Test null input
        List<Integer> resultNull = stubConverter.toDtoList(null);
        assertNotNull(resultNull);
        assertTrue(resultNull.isEmpty());

        // Test non-null input
        List<Integer> result = stubConverter.toDtoList(List.of("1", "2"));
        assertEquals(2, result.size());
        assertEquals(1, result.get(0));
        assertEquals(2, result.get(1));
    }

    @Test
    void testToEntityList() {
        // Test null input
        List<String> resultNull = stubConverter.toEntityList(null);
        assertNotNull(resultNull);
        assertTrue(resultNull.isEmpty());

        // Test non-null input
        List<String> result = stubConverter.toEntityList(List.of(1, 2));
        assertEquals(2, result.size());
        assertEquals("1", result.get(0));
        assertEquals("2", result.get(1));
    }
}
