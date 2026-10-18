/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeDefinition;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeDefinitionScope;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeScope;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.AbstractPostgresRepositoryTest;

class PolicyAttributeDefinitionScopeRepositoryTest extends AbstractPostgresRepositoryTest {

    @Autowired
    private PolicyAttributeDefinitionRepository policyAttributeDefinitionRepository;

    @Autowired
    private PolicyAttributeScopeRepository policyAttributeScopeRepository;

    @Autowired
    private PolicyAttributeDefinitionScopeRepository policyAttributeDefinitionScopeRepository;

    private PolicyAttributeDefinition persistDefinition(String name) {
        PolicyAttributeDefinition definition = new PolicyAttributeDefinition();
        definition.setNamespace("policy");
        definition.setName(name);
        definition.setDescription("Test attribute definition");
        definition.setDataType("STRING");
        definition.setCreatedAt(Timestamp.from(Instant.now()));
        definition.setCreatedBy("test");
        return policyAttributeDefinitionRepository.saveAndFlush(definition);
    }

    private static PolicyAttributeDefinitionScope newBinding(
            PolicyAttributeDefinition definition, PolicyAttributeScope scope, boolean required) {
        PolicyAttributeDefinitionScope binding = new PolicyAttributeDefinitionScope();
        binding.setAttributeDefinition(definition);
        binding.setAttributeScope(scope);
        binding.setRequired(required);
        binding.setCreatedAt(Timestamp.from(Instant.now()));
        binding.setCreatedBy("test");
        return binding;
    }

    @Test
    void findByAttributeDefinitionId_returnsAllBoundScopes() {
        PolicyAttributeDefinition definition = persistDefinition("multi-scope-attr");
        PolicyAttributeScope productScope =
                policyAttributeScopeRepository.findByCode("PRODUCT").orElseThrow();
        PolicyAttributeScope consumerScope =
                policyAttributeScopeRepository.findByCode("CONSUMER").orElseThrow();

        policyAttributeDefinitionScopeRepository.saveAndFlush(newBinding(definition, productScope, true));
        policyAttributeDefinitionScopeRepository.saveAndFlush(newBinding(definition, consumerScope, false));

        List<PolicyAttributeDefinitionScope> bindings =
                policyAttributeDefinitionScopeRepository.findByAttributeDefinitionId(definition.getId());

        assertThat(bindings).hasSize(2);
        assertThat(bindings)
                .extracting(b -> b.getAttributeScope().getId())
                .containsExactlyInAnyOrder(productScope.getId(), consumerScope.getId());
    }

    @Test
    void save_rejectsDuplicateDefinitionScopePair() {
        PolicyAttributeDefinition definition = persistDefinition("duplicate-binding-attr");
        PolicyAttributeScope productScope =
                policyAttributeScopeRepository.findByCode("PRODUCT").orElseThrow();
        policyAttributeDefinitionScopeRepository.saveAndFlush(newBinding(definition, productScope, false));

        PolicyAttributeDefinitionScope duplicate = newBinding(definition, productScope, true);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> policyAttributeDefinitionScopeRepository.saveAndFlush(duplicate));
    }
}
