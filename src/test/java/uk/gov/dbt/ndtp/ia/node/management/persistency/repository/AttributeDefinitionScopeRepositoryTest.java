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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeDefinition;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeDefinitionScope;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeScope;

class AttributeDefinitionScopeRepositoryTest extends AbstractPostgresRepositoryTest {

    @Autowired
    private AttributeDefinitionRepository attributeDefinitionRepository;

    @Autowired
    private AttributeScopeRepository attributeScopeRepository;

    @Autowired
    private AttributeDefinitionScopeRepository attributeDefinitionScopeRepository;

    private AttributeDefinition persistDefinition(String name) {
        AttributeDefinition definition = new AttributeDefinition();
        definition.setNamespace("policy");
        definition.setName(name);
        definition.setDescription("Test attribute definition");
        definition.setDataType("STRING");
        definition.setCreatedAt(Timestamp.from(Instant.now()));
        definition.setCreatedBy("test");
        return attributeDefinitionRepository.saveAndFlush(definition);
    }

    private static AttributeDefinitionScope newBinding(
            AttributeDefinition definition, AttributeScope scope, boolean required) {
        AttributeDefinitionScope binding = new AttributeDefinitionScope();
        binding.setAttributeDefinition(definition);
        binding.setAttributeScope(scope);
        binding.setRequired(required);
        binding.setCreatedAt(Timestamp.from(Instant.now()));
        binding.setCreatedBy("test");
        return binding;
    }

    @Test
    void findByAttributeDefinitionId_returnsAllBoundScopes() {
        AttributeDefinition definition = persistDefinition("multi-scope-attr");
        AttributeScope productScope =
                attributeScopeRepository.findByCode("PRODUCT").orElseThrow();
        AttributeScope consumerScope =
                attributeScopeRepository.findByCode("CONSUMER").orElseThrow();

        attributeDefinitionScopeRepository.saveAndFlush(newBinding(definition, productScope, true));
        attributeDefinitionScopeRepository.saveAndFlush(newBinding(definition, consumerScope, false));

        List<AttributeDefinitionScope> bindings =
                attributeDefinitionScopeRepository.findByAttributeDefinitionId(definition.getId());

        assertThat(bindings).hasSize(2);
        assertThat(bindings)
                .extracting(b -> b.getAttributeScope().getId())
                .containsExactlyInAnyOrder(productScope.getId(), consumerScope.getId());
    }

    @Test
    void save_rejectsDuplicateDefinitionScopePair() {
        AttributeDefinition definition = persistDefinition("duplicate-binding-attr");
        AttributeScope productScope =
                attributeScopeRepository.findByCode("PRODUCT").orElseThrow();
        attributeDefinitionScopeRepository.saveAndFlush(newBinding(definition, productScope, false));

        AttributeDefinitionScope duplicate = newBinding(definition, productScope, true);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> attributeDefinitionScopeRepository.saveAndFlush(duplicate));
    }
}
