/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeDefinition;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeDefinitionScope;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeScope;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeValue;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.AbstractPostgresRepositoryTest;

class PolicyAttributeValueRepositoryTest extends AbstractPostgresRepositoryTest {

    @DynamicPropertySource
    static void statisticsProperty(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    @Autowired
    private PolicyAttributeDefinitionRepository policyAttributeDefinitionRepository;

    @Autowired
    private PolicyAttributeScopeRepository policyAttributeScopeRepository;

    @Autowired
    private PolicyAttributeDefinitionScopeRepository policyAttributeDefinitionScopeRepository;

    @Autowired
    private PolicyAttributeValueRepository policyAttributeValueRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private PolicyAttributeDefinitionScope persistProductScopedBinding(String attributeName) {
        return persistScopedBinding(attributeName, "PRODUCT");
    }

    private PolicyAttributeDefinitionScope persistScopedBinding(String attributeName, String scopeCode) {
        PolicyAttributeDefinition definition = new PolicyAttributeDefinition();
        definition.setNamespace("policy");
        definition.setName(attributeName);
        definition.setDescription("Test attribute definition");
        definition.setDataType("STRING");
        definition.setCreatedAt(Timestamp.from(Instant.now()));
        definition.setCreatedBy("test");
        definition = policyAttributeDefinitionRepository.saveAndFlush(definition);

        PolicyAttributeScope scope =
                policyAttributeScopeRepository.findByCode(scopeCode).orElseThrow();

        PolicyAttributeDefinitionScope binding = new PolicyAttributeDefinitionScope();
        binding.setAttributeDefinition(definition);
        binding.setAttributeScope(scope);
        binding.setRequired(false);
        binding.setCreatedAt(Timestamp.from(Instant.now()));
        binding.setCreatedBy("test");
        return policyAttributeDefinitionScopeRepository.saveAndFlush(binding);
    }

    private static PolicyAttributeValue newValue(PolicyAttributeDefinitionScope binding, Long entityId, String json) {
        PolicyAttributeValue value = new PolicyAttributeValue();
        value.setAttributeDefinitionScope(binding);
        value.setEntityId(entityId);
        value.setValue(json);
        value.setCreatedAt(Timestamp.from(Instant.now()));
        value.setCreatedBy("test");
        return value;
    }

    @Test
    void findLiveValue_returnsNonDeletedValue() {
        PolicyAttributeDefinitionScope binding = persistProductScopedBinding("live-value-attr");
        policyAttributeValueRepository.saveAndFlush(newValue(binding, 1001L, "\"gold\""));

        List<PolicyAttributeValue> live =
                policyAttributeValueRepository.findByAttributeDefinitionScopeIdAndEntityIdAndIsDeletedFalse(
                        binding.getId(), 1001L);

        assertThat(live).hasSize(1);
        assertThat(live.get(0).getValue()).isEqualTo("\"gold\"");
    }

    @Test
    void findLiveValue_excludesSoftDeletedValue() {
        PolicyAttributeDefinitionScope binding = persistProductScopedBinding("soft-deleted-attr");
        PolicyAttributeValue value = newValue(binding, 1002L, "\"silver\"");
        value.setIsDeleted(true);
        policyAttributeValueRepository.saveAndFlush(value);

        List<PolicyAttributeValue> live =
                policyAttributeValueRepository.findByAttributeDefinitionScopeIdAndEntityIdAndIsDeletedFalse(
                        binding.getId(), 1002L);

        assertThat(live).isEmpty();
    }

    @Test
    void save_rejectsExactDuplicateLiveValue() {
        PolicyAttributeDefinitionScope binding = persistProductScopedBinding("duplicate-value-attr");
        policyAttributeValueRepository.saveAndFlush(newValue(binding, 1003L, "\"gold\""));

        PolicyAttributeValue duplicate = newValue(binding, 1003L, "\"gold\"");

        assertThrows(
                DataIntegrityViolationException.class, () -> policyAttributeValueRepository.saveAndFlush(duplicate));
    }

    @Test
    void save_acceptsDistinctValueForSameBindingAndEntity() {
        // uq_attr_value_live keys on (scope, entity, value) - it is an idempotency guard against exact
        // duplicates, not a single-valuedness constraint, so a different value is allowed. See design.md.
        PolicyAttributeDefinitionScope binding = persistProductScopedBinding("multi-valued-attr");
        policyAttributeValueRepository.saveAndFlush(newValue(binding, 1004L, "\"gold\""));

        policyAttributeValueRepository.saveAndFlush(newValue(binding, 1004L, "\"silver\""));

        List<PolicyAttributeValue> live =
                policyAttributeValueRepository.findByAttributeDefinitionScopeIdAndEntityIdAndIsDeletedFalse(
                        binding.getId(), 1004L);
        assertThat(live)
                .hasSize(2)
                .extracting(PolicyAttributeValue::getValue)
                .containsExactlyInAnyOrder("\"gold\"", "\"silver\"");
    }

    @Test
    void save_allowsSameValueAgainAfterPriorDuplicateIsSoftDeleted() {
        PolicyAttributeDefinitionScope binding = persistProductScopedBinding("resurrected-attr");
        PolicyAttributeValue first = newValue(binding, 1005L, "\"gold\"");
        first = policyAttributeValueRepository.saveAndFlush(first);
        first.setIsDeleted(true);
        policyAttributeValueRepository.saveAndFlush(first);

        PolicyAttributeValue resurrected = newValue(binding, 1005L, "\"gold\"");
        policyAttributeValueRepository.saveAndFlush(resurrected);

        List<PolicyAttributeValue> live =
                policyAttributeValueRepository.findByAttributeDefinitionScopeIdAndEntityIdAndIsDeletedFalse(
                        binding.getId(), 1005L);
        assertThat(live).hasSize(1);
        assertThat(live.get(0).getValue()).isEqualTo("\"gold\"");
    }

    // findLiveByEntityIdAndScopeCode - task 1.1

    @Test
    void findLiveByEntityIdAndScopeCode_returnsMatchWithDefinitionEagerlyFetched() {
        PolicyAttributeDefinitionScope binding = persistScopedBinding("producer-tier", "PRODUCER");
        policyAttributeValueRepository.saveAndFlush(newValue(binding, 2001L, "\"gold\""));

        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        sessionFactory.getStatistics().clear();

        List<PolicyAttributeValue> live =
                policyAttributeValueRepository.findLiveByEntityIdAndScopeCode(2001L, "PRODUCER");

        assertThat(live).hasSize(1);
        PolicyAttributeValue found = live.get(0);
        // Subscribing the definition must not trigger an additional query - proves the JOIN FETCH,
        // not lazy N+1 loading, populated it.
        assertThat(found.getAttributeDefinitionScope().getAttributeDefinition().getName())
                .isEqualTo("producer-tier");
        assertThat(sessionFactory.getStatistics().getQueryExecutionCount()).isEqualTo(1);
    }

    @Test
    void findLiveByEntityIdAndScopeCode_excludesSoftDeletedValue() {
        PolicyAttributeDefinitionScope binding = persistScopedBinding("producer-tier-deleted", "PRODUCER");
        PolicyAttributeValue value = newValue(binding, 2002L, "\"gold\"");
        value.setIsDeleted(true);
        policyAttributeValueRepository.saveAndFlush(value);

        List<PolicyAttributeValue> live =
                policyAttributeValueRepository.findLiveByEntityIdAndScopeCode(2002L, "PRODUCER");

        assertThat(live).isEmpty();
    }

    @Test
    void findLiveByEntityIdAndScopeCode_excludesValueUnderDifferentScope() {
        PolicyAttributeDefinitionScope binding = persistScopedBinding("consumer-only-tier", "CONSUMER");
        policyAttributeValueRepository.saveAndFlush(newValue(binding, 2003L, "\"gold\""));

        List<PolicyAttributeValue> live =
                policyAttributeValueRepository.findLiveByEntityIdAndScopeCode(2003L, "PRODUCER");

        assertThat(live).isEmpty();
    }

    @Test
    void findLiveByEntityIdAndScopeCode_returnsEmptyForUnknownEntity() {
        List<PolicyAttributeValue> live =
                policyAttributeValueRepository.findLiveByEntityIdAndScopeCode(999999L, "PRODUCER");

        assertThat(live).isEmpty();
    }
}
