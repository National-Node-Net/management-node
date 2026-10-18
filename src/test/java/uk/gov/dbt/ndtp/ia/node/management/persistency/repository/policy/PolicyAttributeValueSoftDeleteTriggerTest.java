/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Consumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Producer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Product;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.organisation.Organisation;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeDefinition;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeDefinitionScope;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeScope;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy.PolicyAttributeValue;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.AbstractPostgresRepositoryTest;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ConsumerRepository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProducerRepository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductConsumerRepository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductRepository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.organisation.OrganisationRepository;

/**
 * What happens to an entity's policy attribute values when the entity is deleted.
 *
 * <p>Until {@code V20260922120000__drop_policy_attribute_soft_delete_trigger.sql} a database
 * trigger marked them {@code is_deleted}. That function resolved its table names unqualified, so
 * PL/pgSQL resolved them against the caller's {@code search_path}, and any session without the
 * schema on its path could not delete from these tables at all. The triggers were dropped.
 *
 * <p>These tests pin what is true now: <strong>nothing cleans the values up</strong>.
 * {@code policy_attribute_value.entity_id} is polymorphic, so it has no foreign key and no
 * cascade, and the rows stay live after their entity is gone. They remain visible through
 * {@code policy_attribute_live_value} and can still be matched by {@code entity_id}.
 *
 * <p>The tests are deliberately kept rather than deleted with the triggers: whatever deletes one
 * of these entities is now responsible for soft-deleting its attribute values in the same
 * transaction, and these assertions are what will fail if a trigger is quietly reintroduced or an
 * application-side cleanup is added without updating the documented contract.
 */
class PolicyAttributeValueSoftDeleteTriggerTest extends AbstractPostgresRepositoryTest {

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
    private PolicyAttributeScopeRepository policyAttributeScopeRepository;

    @Autowired
    private PolicyAttributeDefinitionRepository policyAttributeDefinitionRepository;

    @Autowired
    private PolicyAttributeDefinitionScopeRepository policyAttributeDefinitionScopeRepository;

    @Autowired
    private PolicyAttributeValueRepository policyAttributeValueRepository;

    @Autowired
    private TestEntityManager testEntityManager;

    private PolicyAttributeDefinitionScope bindingFor(String scopeCode, String attributeName) {
        PolicyAttributeDefinition definition = new PolicyAttributeDefinition();
        definition.setNamespace("policy");
        definition.setName(attributeName);
        definition.setDescription("Trigger test attribute definition");
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

    private Long persistLiveValue(PolicyAttributeDefinitionScope binding, Long entityId) {
        PolicyAttributeValue value = new PolicyAttributeValue();
        value.setAttributeDefinitionScope(binding);
        value.setEntityId(entityId);
        value.setValue("\"trigger-test-value\"");
        value.setCreatedAt(Timestamp.from(Instant.now()));
        value.setCreatedBy("test");
        return policyAttributeValueRepository.saveAndFlush(value).getId();
    }

    private Organisation persistOrganisation() {
        Organisation organisation = new Organisation();
        organisation.setName("Trigger Test Org");
        // organisation_key is NOT NULL and unique; each test persists its own organisation, so the
        // key has to be unique per call rather than a fixed literal.
        organisation.setOrganisationKey("TRIG_" + UUID.randomUUID().toString().substring(0, 8));
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
    void deletingOrganisation_leavesItsAttributeValuesLive() {
        Organisation organisation = persistOrganisation();
        PolicyAttributeDefinitionScope binding = bindingFor("ORGANISATION", "org-trigger-attr");
        Long valueId = persistLiveValue(binding, organisation.getId());

        organisationRepository.delete(organisation);
        organisationRepository.flush();
        testEntityManager.clear();

        assertThat(policyAttributeValueRepository.findById(valueId))
                .isPresent()
                .get()
                .satisfies(v -> assertThat(v.getIsDeleted()).isFalse());
    }

    @Test
    void deletingConsumer_leavesItsAttributeValuesLive() {
        Organisation organisation = persistOrganisation();
        Consumer consumer = persistConsumer(organisation);
        PolicyAttributeDefinitionScope binding = bindingFor("CONSUMER", "consumer-trigger-attr");
        Long valueId = persistLiveValue(binding, consumer.getId());

        consumerRepository.delete(consumer);
        consumerRepository.flush();
        testEntityManager.clear();

        assertThat(policyAttributeValueRepository.findById(valueId))
                .isPresent()
                .get()
                .satisfies(v -> assertThat(v.getIsDeleted()).isFalse());
    }

    @Test
    void deletingProducer_leavesItsAttributeValuesLive() {
        Organisation organisation = persistOrganisation();
        Producer producer = persistProducer(organisation);
        PolicyAttributeDefinitionScope binding = bindingFor("PRODUCER", "producer-trigger-attr");
        Long valueId = persistLiveValue(binding, producer.getId());

        producerRepository.delete(producer);
        producerRepository.flush();
        testEntityManager.clear();

        assertThat(policyAttributeValueRepository.findById(valueId))
                .isPresent()
                .get()
                .satisfies(v -> assertThat(v.getIsDeleted()).isFalse());
    }

    @Test
    void deletingProduct_leavesItsAttributeValuesLive() {
        Organisation organisation = persistOrganisation();
        Producer producer = persistProducer(organisation);
        Product product = persistProduct(producer);
        PolicyAttributeDefinitionScope binding = bindingFor("PRODUCT", "product-trigger-attr");
        Long valueId = persistLiveValue(binding, product.getId());

        productRepository.delete(product);
        productRepository.flush();
        testEntityManager.clear();

        assertThat(policyAttributeValueRepository.findById(valueId))
                .isPresent()
                .get()
                .satisfies(v -> assertThat(v.getIsDeleted()).isFalse());
    }

    @Test
    void deletingProductConsumer_leavesItsAttributeValuesLive() {
        Organisation organisation = persistOrganisation();
        Producer producer = persistProducer(organisation);
        Product product = persistProduct(producer);
        Consumer consumer = persistConsumer(organisation);
        ProductConsumer productConsumer = persistProductConsumer(product, consumer);
        PolicyAttributeDefinitionScope binding = bindingFor("SUBSCRIPTION", "subscription-trigger-attr");
        Long valueId = persistLiveValue(binding, productConsumer.getId());

        productConsumerRepository.delete(productConsumer);
        productConsumerRepository.flush();
        testEntityManager.clear();

        assertThat(policyAttributeValueRepository.findById(valueId))
                .isPresent()
                .get()
                .satisfies(v -> assertThat(v.getIsDeleted()).isFalse());
    }

    @Test
    void deletingEntityWithNoAttributeValues_succeedsAndLeavesAttributeValueTableUntouched() {
        Organisation organisation = persistOrganisation();
        long countBefore = policyAttributeValueRepository.count();

        organisationRepository.delete(organisation);
        organisationRepository.flush();

        assertThat(organisationRepository.existsById(organisation.getId())).isFalse();
        assertThat(policyAttributeValueRepository.count()).isEqualTo(countBefore);
    }
}
