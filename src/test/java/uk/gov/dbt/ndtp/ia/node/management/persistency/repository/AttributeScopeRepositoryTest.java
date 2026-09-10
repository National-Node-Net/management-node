/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeScope;

class AttributeScopeRepositoryTest extends AbstractPostgresRepositoryTest {

    @Autowired
    private AttributeScopeRepository attributeScopeRepository;

    @Test
    void findByCode_returnsSeededScope() {
        Optional<AttributeScope> found = attributeScopeRepository.findByCode("PRODUCT");

        assertThat(found).isPresent();
        assertThat(found.get().getTableName()).isEqualTo("product");
    }

    @Test
    void findByCode_returnsEmptyForUnknownCode() {
        Optional<AttributeScope> found = attributeScopeRepository.findByCode("DOES_NOT_EXIST");

        assertThat(found).isEmpty();
    }

    @Test
    void save_rejectsDuplicateCode() {
        AttributeScope duplicate = new AttributeScope();
        duplicate.setCode("PRODUCT");
        duplicate.setTableName("product");
        duplicate.setDescription("Duplicate of the seeded PRODUCT scope");

        assertThrows(DataIntegrityViolationException.class, () -> attributeScopeRepository.saveAndFlush(duplicate));
    }
}
