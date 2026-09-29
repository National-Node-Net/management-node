/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;

class ModelMapperConfigTest {

    @Test
    void modelMapper_shouldReturnConfiguredModelMapper() {
        // Arrange
        ModelMapperConfig config = new ModelMapperConfig();

        // Act
        ModelMapper modelMapper = config.modelMapper();

        // Assert
        assertNotNull(modelMapper);
        assertEquals(MatchingStrategies.STRICT, modelMapper.getConfiguration().getMatchingStrategy());
    }
}
