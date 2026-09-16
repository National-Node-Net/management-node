/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeDefinition;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.AbstractPostgresRepositoryTest;

class PolicyAttributeDefinitionRepositoryTest extends AbstractPostgresRepositoryTest {

    @Autowired
    private PolicyAttributeDefinitionRepository policyAttributeDefinitionRepository;

    private static PolicyAttributeDefinition newDefinition(String namespace, String name) {
        PolicyAttributeDefinition definition = new PolicyAttributeDefinition();
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
        policyAttributeDefinitionRepository.saveAndFlush(newDefinition("policy", "risk-tier"));

        Optional<PolicyAttributeDefinition> found =
                policyAttributeDefinitionRepository.findByNamespaceAndName("policy", "risk-tier");

        assertThat(found).isPresent();
        assertThat(found.get().getDataType()).isEqualTo("STRING");
        assertThat(found.get().getMultiValued()).isFalse();
        assertThat(found.get().getSensitive()).isFalse();
    }

    @Test
    void findByNamespaceAndName_returnsEmptyForUnknownPair() {
        Optional<PolicyAttributeDefinition> found =
                policyAttributeDefinitionRepository.findByNamespaceAndName("nope", "nope");

        assertThat(found).isEmpty();
    }

    @Test
    void save_rejectsDuplicateNamespaceAndName() {
        policyAttributeDefinitionRepository.saveAndFlush(newDefinition("policy", "duplicate-check"));
        PolicyAttributeDefinition duplicate = newDefinition("policy", "duplicate-check");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> policyAttributeDefinitionRepository.saveAndFlush(duplicate));
    }
}
