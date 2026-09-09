/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeDefinition;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeDefinitionScope;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeScope;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeValue;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Consumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Organisation;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Producer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Product;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.ProductConsumer;

/**
 * Verifies the migration's five {@code AFTER DELETE} triggers, which soft-delete
 * {@code attribute_value} rows scoped to the deleted owning entity rather than
 * leaving them orphaned.
 */
class AttributeValueSoftDeleteTriggerTest extends AbstractPostgresRepositoryTest {

    @Autowired
    private OrganisationRepository organisationRepository;

    @Autowired
    private ProducerRepository producerRepository;

    @Autowired
    private ConsumerRepository consumerRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductConsumerRepository productConsumerRepository;

    @Autowired
    private AttributeScopeRepository attributeScopeRepository;

    @Autowired
    private AttributeDefinitionRepository attributeDefinitionRepository;

    @Autowired
    private AttributeDefinitionScopeRepository attributeDefinitionScopeRepository;

    @Autowired
    private AttributeValueRepository attributeValueRepository;

    @Autowired
    private TestEntityManager testEntityManager;

    private AttributeDefinitionScope bindingFor(String scopeCode, String attributeName) {
        AttributeDefinition definition = new AttributeDefinition();
        definition.setNamespace("policy");
        definition.setName(attributeName);
        definition.setDescription("Trigger test attribute definition");
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

    private Long persistLiveValue(AttributeDefinitionScope binding, Long entityId) {
        AttributeValue value = new AttributeValue();
        value.setAttributeDefinitionScope(binding);
        value.setEntityId(entityId);
        value.setValue("\"trigger-test-value\"");
        value.setCreatedAt(Timestamp.from(Instant.now()));
        value.setCreatedBy("test");
        return attributeValueRepository.saveAndFlush(value).getId();
    }

    private Organisation persistOrganisation() {
        Organisation organisation = new Organisation();
        organisation.setName("Trigger Test Org");
        return organisationRepository.saveAndFlush(organisation);
    }

    private Consumer persistConsumer(Organisation organisation) {
        Consumer consumer = new Consumer();
        consumer.setName("Trigger Test Consumer");
        consumer.setOrg(organisation);
        consumer.setIdpClientId("trigger-test-consumer");
        consumer.setScheduleType("cron");
        return consumerRepository.saveAndFlush(consumer);
    }

    private Producer persistProducer(Organisation organisation) {
        Producer producer = new Producer();
        producer.setName("Trigger Test Producer");
        producer.setDescription("Trigger test producer");
        producer.setOrg(organisation);
        producer.setActive(true);
        producer.setHost("localhost");
        producer.setPort(BigDecimal.valueOf(8080));
        producer.setTls(true);
        producer.setIdpClientId("trigger-test-producer");
        return producerRepository.saveAndFlush(producer);
    }

    private Product persistProduct(Producer producer) {
        Product product = new Product();
        product.setName("Trigger Test Product");
        product.setTopic("topic.trigger-test");
        product.setProducer(producer);
        return productRepository.saveAndFlush(product);
    }

    private ProductConsumer persistProductConsumer(Product product, Consumer consumer) {
        ProductConsumer productConsumer = new ProductConsumer();
        productConsumer.setProduct(product);
        productConsumer.setConsumer(consumer);
        productConsumer.setGrantedTs(Timestamp.from(Instant.now()));
        productConsumer.setValidity(BigDecimal.valueOf(30));
        productConsumer.setScheduleType("cron");
        return productConsumerRepository.saveAndFlush(productConsumer);
    }

    @Test
    void deletingOrganisation_softDeletesItsAttributeValues() {
        Organisation organisation = persistOrganisation();
        AttributeDefinitionScope binding = bindingFor("ORGANISATION", "org-trigger-attr");
        Long valueId = persistLiveValue(binding, organisation.getId());

        organisationRepository.delete(organisation);
        organisationRepository.flush();
        testEntityManager.clear();

        assertThat(attributeValueRepository.findById(valueId)).isPresent().get().satisfies(v -> assertThat(
                        v.getIsDeleted())
                .isTrue());
    }

    @Test
    void deletingConsumer_softDeletesItsAttributeValues() {
        Organisation organisation = persistOrganisation();
        Consumer consumer = persistConsumer(organisation);
        AttributeDefinitionScope binding = bindingFor("CONSUMER", "consumer-trigger-attr");
        Long valueId = persistLiveValue(binding, consumer.getId());

        consumerRepository.delete(consumer);
        consumerRepository.flush();
        testEntityManager.clear();

        assertThat(attributeValueRepository.findById(valueId)).isPresent().get().satisfies(v -> assertThat(
                        v.getIsDeleted())
                .isTrue());
    }

    @Test
    void deletingProducer_softDeletesItsAttributeValues() {
        Organisation organisation = persistOrganisation();
        Producer producer = persistProducer(organisation);
        AttributeDefinitionScope binding = bindingFor("PRODUCER", "producer-trigger-attr");
        Long valueId = persistLiveValue(binding, producer.getId());

        producerRepository.delete(producer);
        producerRepository.flush();
        testEntityManager.clear();

        assertThat(attributeValueRepository.findById(valueId)).isPresent().get().satisfies(v -> assertThat(
                        v.getIsDeleted())
                .isTrue());
    }

    @Test
    void deletingProduct_softDeletesItsAttributeValues() {
        Organisation organisation = persistOrganisation();
        Producer producer = persistProducer(organisation);
        Product product = persistProduct(producer);
        AttributeDefinitionScope binding = bindingFor("PRODUCT", "product-trigger-attr");
        Long valueId = persistLiveValue(binding, product.getId());

        productRepository.delete(product);
        productRepository.flush();
        testEntityManager.clear();

        assertThat(attributeValueRepository.findById(valueId)).isPresent().get().satisfies(v -> assertThat(
                        v.getIsDeleted())
                .isTrue());
    }

    @Test
    void deletingProductConsumer_softDeletesItsAttributeValues() {
        Organisation organisation = persistOrganisation();
        Producer producer = persistProducer(organisation);
        Product product = persistProduct(producer);
        Consumer consumer = persistConsumer(organisation);
        ProductConsumer productConsumer = persistProductConsumer(product, consumer);
        AttributeDefinitionScope binding = bindingFor("SUBSCRIPTION", "subscription-trigger-attr");
        Long valueId = persistLiveValue(binding, productConsumer.getId());

        productConsumerRepository.delete(productConsumer);
        productConsumerRepository.flush();
        testEntityManager.clear();

        assertThat(attributeValueRepository.findById(valueId)).isPresent().get().satisfies(v -> assertThat(
                        v.getIsDeleted())
                .isTrue());
    }

    @Test
    void deletingEntityWithNoAttributeValues_succeedsAndLeavesAttributeValueTableUntouched() {
        Organisation organisation = persistOrganisation();
        long countBefore = attributeValueRepository.count();

        organisationRepository.delete(organisation);
        organisationRepository.flush();

        assertThat(organisationRepository.existsById(organisation.getId())).isFalse();
        assertThat(attributeValueRepository.count()).isEqualTo(countBefore);
    }
}
