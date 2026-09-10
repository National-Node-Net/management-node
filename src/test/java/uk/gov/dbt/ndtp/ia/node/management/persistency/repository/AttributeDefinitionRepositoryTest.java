/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeDefinition;

class AttributeDefinitionRepositoryTest extends AbstractPostgresRepositoryTest {

    @Autowired
    private AttributeDefinitionRepository attributeDefinitionRepository;

    private static AttributeDefinition newDefinition(String namespace, String name) {
        AttributeDefinition definition = new AttributeDefinition();
        definition.setNamespace(namespace);
        definition.setName(name);
        definition.setDescription("Test attribute definition");
        definition.setDataType("STRING");
        definition.setCreatedAt(Timestamp.from(Instant.now()));
        definition.setCreatedBy("test");
        return definition;
    }

    @Test
    void findByNamespaceAndName_returnsPersistedDefinition() {
        attributeDefinitionRepository.saveAndFlush(newDefinition("policy", "risk-tier"));

        Optional<AttributeDefinition> found =
                attributeDefinitionRepository.findByNamespaceAndName("policy", "risk-tier");

        assertThat(found).isPresent();
        assertThat(found.get().getDataType()).isEqualTo("STRING");
        assertThat(found.get().getMultiValued()).isFalse();
        assertThat(found.get().getSensitive()).isFalse();
    }

    @Test
    void findByNamespaceAndName_returnsEmptyForUnknownPair() {
        Optional<AttributeDefinition> found = attributeDefinitionRepository.findByNamespaceAndName("nope", "nope");

        assertThat(found).isEmpty();
    }

    @Test
    void save_rejectsDuplicateNamespaceAndName() {
        attributeDefinitionRepository.saveAndFlush(newDefinition("policy", "duplicate-check"));
        AttributeDefinition duplicate = newDefinition("policy", "duplicate-check");

        assertThrows(
                DataIntegrityViolationException.class, () -> attributeDefinitionRepository.saveAndFlush(duplicate));
    }
}
