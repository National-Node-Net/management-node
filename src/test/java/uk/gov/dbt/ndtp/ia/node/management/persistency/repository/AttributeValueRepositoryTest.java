/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository;

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
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeDefinition;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeDefinitionScope;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeScope;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeValue;

class AttributeValueRepositoryTest extends AbstractPostgresRepositoryTest {

    @DynamicPropertySource
    static void statisticsProperty(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    @Autowired
    private AttributeDefinitionRepository attributeDefinitionRepository;

    @Autowired
    private AttributeScopeRepository attributeScopeRepository;

    @Autowired
    private AttributeDefinitionScopeRepository attributeDefinitionScopeRepository;

    @Autowired
    private AttributeValueRepository attributeValueRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private AttributeDefinitionScope persistProductScopedBinding(String attributeName) {
        return persistScopedBinding(attributeName, "PRODUCT");
    }

    private AttributeDefinitionScope persistScopedBinding(String attributeName, String scopeCode) {
        AttributeDefinition definition = new AttributeDefinition();
        definition.setNamespace("policy");
        definition.setName(attributeName);
        definition.setDescription("Test attribute definition");
        definition.setDataType("STRING");
        definition.setCreatedAt(Timestamp.from(Instant.now()));
        definition.setCreatedBy("test");
        definition = attributeDefinitionRepository.saveAndFlush(definition);

        AttributeScope scope = attributeScopeRepository.findByCode(scopeCode).orElseThrow();

        AttributeDefinitionScope binding = new AttributeDefinitionScope();
        binding.setAttributeDefinition(definition);
        binding.setAttributeScope(scope);
        binding.setRequired(false);
        binding.setCreatedAt(Timestamp.from(Instant.now()));
        binding.setCreatedBy("test");
        return attributeDefinitionScopeRepository.saveAndFlush(binding);
    }

    private static AttributeValue newValue(AttributeDefinitionScope binding, Long entityId, String json) {
        AttributeValue value = new AttributeValue();
        value.setAttributeDefinitionScope(binding);
        value.setEntityId(entityId);
        value.setValue(json);
        value.setCreatedAt(Timestamp.from(Instant.now()));
        value.setCreatedBy("test");
        return value;
    }

    @Test
    void findLiveValue_returnsNonDeletedValue() {
        AttributeDefinitionScope binding = persistProductScopedBinding("live-value-attr");
        attributeValueRepository.saveAndFlush(newValue(binding, 1001L, "\"gold\""));

        List<AttributeValue> live =
                attributeValueRepository.findByAttributeDefinitionScopeIdAndEntityIdAndIsDeletedFalse(
                        binding.getId(), 1001L);

        assertThat(live).hasSize(1);
        assertThat(live.get(0).getValue()).isEqualTo("\"gold\"");
    }

    @Test
    void findLiveValue_excludesSoftDeletedValue() {
        AttributeDefinitionScope binding = persistProductScopedBinding("soft-deleted-attr");
        AttributeValue value = newValue(binding, 1002L, "\"silver\"");
        value.setIsDeleted(true);
        attributeValueRepository.saveAndFlush(value);

        List<AttributeValue> live =
                attributeValueRepository.findByAttributeDefinitionScopeIdAndEntityIdAndIsDeletedFalse(
                        binding.getId(), 1002L);

        assertThat(live).isEmpty();
    }

    @Test
    void save_rejectsExactDuplicateLiveValue() {
        AttributeDefinitionScope binding = persistProductScopedBinding("duplicate-value-attr");
        attributeValueRepository.saveAndFlush(newValue(binding, 1003L, "\"gold\""));

        AttributeValue duplicate = newValue(binding, 1003L, "\"gold\"");

        assertThrows(DataIntegrityViolationException.class, () -> attributeValueRepository.saveAndFlush(duplicate));
    }

    @Test
    void save_acceptsDistinctValueForSameBindingAndEntity() {
        // uq_attr_value_live keys on (scope, entity, value) - it is an idempotency guard against exact
        // duplicates, not a single-valuedness constraint, so a different value is allowed. See design.md.
        AttributeDefinitionScope binding = persistProductScopedBinding("multi-valued-attr");
        attributeValueRepository.saveAndFlush(newValue(binding, 1004L, "\"gold\""));

        attributeValueRepository.saveAndFlush(newValue(binding, 1004L, "\"silver\""));

        List<AttributeValue> live =
                attributeValueRepository.findByAttributeDefinitionScopeIdAndEntityIdAndIsDeletedFalse(
                        binding.getId(), 1004L);
        assertThat(live)
                .hasSize(2)
                .extracting(AttributeValue::getValue)
                .containsExactlyInAnyOrder("\"gold\"", "\"silver\"");
    }

    @Test
    void save_allowsSameValueAgainAfterPriorDuplicateIsSoftDeleted() {
        AttributeDefinitionScope binding = persistProductScopedBinding("resurrected-attr");
        AttributeValue first = newValue(binding, 1005L, "\"gold\"");
        first = attributeValueRepository.saveAndFlush(first);
        first.setIsDeleted(true);
        attributeValueRepository.saveAndFlush(first);

        AttributeValue resurrected = newValue(binding, 1005L, "\"gold\"");
        attributeValueRepository.saveAndFlush(resurrected);

        List<AttributeValue> live =
                attributeValueRepository.findByAttributeDefinitionScopeIdAndEntityIdAndIsDeletedFalse(
                        binding.getId(), 1005L);
        assertThat(live).hasSize(1);
        assertThat(live.get(0).getValue()).isEqualTo("\"gold\"");
    }

    // findLiveByEntityIdAndScopeCode - task 1.1

    @Test
    void findLiveByEntityIdAndScopeCode_returnsMatchWithDefinitionEagerlyFetched() {
        AttributeDefinitionScope binding = persistScopedBinding("producer-tier", "PRODUCER");
        attributeValueRepository.saveAndFlush(newValue(binding, 2001L, "\"gold\""));

        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        sessionFactory.getStatistics().clear();

        List<AttributeValue> live = attributeValueRepository.findLiveByEntityIdAndScopeCode(2001L, "PRODUCER");

        assertThat(live).hasSize(1);
        AttributeValue found = live.get(0);
        // Accessing the definition must not trigger an additional query - proves the JOIN FETCH,
        // not lazy N+1 loading, populated it.
        assertThat(found.getAttributeDefinitionScope().getAttributeDefinition().getName())
                .isEqualTo("producer-tier");
        assertThat(sessionFactory.getStatistics().getQueryExecutionCount()).isEqualTo(1);
    }

    @Test
    void findLiveByEntityIdAndScopeCode_excludesSoftDeletedValue() {
        AttributeDefinitionScope binding = persistScopedBinding("producer-tier-deleted", "PRODUCER");
        AttributeValue value = newValue(binding, 2002L, "\"gold\"");
        value.setIsDeleted(true);
        attributeValueRepository.saveAndFlush(value);

        List<AttributeValue> live = attributeValueRepository.findLiveByEntityIdAndScopeCode(2002L, "PRODUCER");

        assertThat(live).isEmpty();
    }

    @Test
    void findLiveByEntityIdAndScopeCode_excludesValueUnderDifferentScope() {
        AttributeDefinitionScope binding = persistScopedBinding("consumer-only-tier", "CONSUMER");
        attributeValueRepository.saveAndFlush(newValue(binding, 2003L, "\"gold\""));

        List<AttributeValue> live = attributeValueRepository.findLiveByEntityIdAndScopeCode(2003L, "PRODUCER");

        assertThat(live).isEmpty();
    }

    @Test
    void findLiveByEntityIdAndScopeCode_returnsEmptyForUnknownEntity() {
        List<AttributeValue> live = attributeValueRepository.findLiveByEntityIdAndScopeCode(999999L, "PRODUCER");

        assertThat(live).isEmpty();
    }
}
