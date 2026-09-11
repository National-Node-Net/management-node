/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.dbt.ndtp.ia.node.management.converter.impl.ConsumerConverter;
import uk.gov.dbt.ndtp.ia.node.management.converter.impl.OrganisationConverter;
import uk.gov.dbt.ndtp.ia.node.management.converter.impl.OrganisationProducerConverter;
import uk.gov.dbt.ndtp.ia.node.management.converter.impl.ProductConsumerConverter;
import uk.gov.dbt.ndtp.ia.node.management.converter.impl.ProductConverter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ConsumerDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.PolicyAttributeDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ProducerConfigDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ProducerDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ProductConsumerDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeDefinition;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeDefinitionScope;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeScope;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.AttributeValue;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Consumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Organisation;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Producer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Product;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.ProductConsumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.ProductType;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.AbstractPostgresRepositoryTest;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.AttributeDefinitionRepository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.AttributeDefinitionScopeRepository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.AttributeScopeRepository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.AttributeValueRepository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.OrganisationRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ConsumerService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProducerService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductConsumerService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.impl.ConsumerServiceImpl;
import uk.gov.dbt.ndtp.ia.node.management.service.data.impl.OrganisationServiceImpl;
import uk.gov.dbt.ndtp.ia.node.management.service.data.impl.PolicyAttributeServiceImpl;
import uk.gov.dbt.ndtp.ia.node.management.service.data.impl.ProducerServiceImpl;
import uk.gov.dbt.ndtp.ia.node.management.service.data.impl.ProductConsumerServiceImpl;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.CertificateValidationProvider;

/**
 * End-to-end coverage (real Postgres, real converters/services, real {@link
 * PolicyAttributeServiceImpl}) of DPAV-3162's core requirement: {@code
 * GET /api/v1/configuration/producer}'s response carries each resource's live policy attributes
 * across all four scopes, and a resource with none gets {@code []}. {@link
 * CertificateValidationProvider} is a plain Mockito mock (not the real certificate chain) - out
 * of scope for what this test needs to prove, already covered elsewhere.
 */
@Transactional
@Import({
    OrganisationProducerConverter.class,
    ProductConverter.class,
    ConsumerConverter.class,
    ProductConsumerConverter.class,
    ProducerServiceImpl.class,
    ConsumerServiceImpl.class,
    ProductConsumerServiceImpl.class
})
class ProducerConfigPolicyAttributesIntegrationTest extends AbstractPostgresRepositoryTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ProducerService producerService;

    @Autowired
    private ConsumerService consumerService;

    @Autowired
    private ProductConsumerService productConsumerService;

    @Autowired
    private AttributeValueRepository attributeValueRepository;

    @Autowired
    private AttributeDefinitionRepository attributeDefinitionRepository;

    @Autowired
    private AttributeDefinitionScopeRepository attributeDefinitionScopeRepository;

    @Autowired
    private AttributeScopeRepository attributeScopeRepository;

    @Autowired
    private OrganisationRepository organisationRepository;

    private ConfigurationProviderImpl configurationProvider() {
        CertificateValidationProvider certificateValidationProvider = mock(CertificateValidationProvider.class);
        when(certificateValidationProvider.findActiveOrganisationIds(any()))
                .thenAnswer(invocation -> new HashSet<>(invocation.getArgument(0)));
        PolicyAttributeService policyAttributeService =
                new PolicyAttributeServiceImpl(attributeValueRepository, new ObjectMapper());
        return new ConfigurationProviderImpl(
                consumerService,
                productConsumerService,
                producerService,
                certificateValidationProvider,
                policyAttributeService,
                new OrganisationServiceImpl(organisationRepository, new OrganisationConverter()));
    }

    private Organisation persistOrganisation(String name) {
        Organisation org = new Organisation();
        org.setName(name);
        org.setOrganisationKey(name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_"));
        entityManager.persist(org);
        return org;
    }

    private Producer persistProducer(Organisation org, String name, String clientId) {
        Producer producer = new Producer();
        producer.setName(name);
        producer.setDescription("test");
        producer.setOrg(org);
        producer.setActive(true);
        producer.setHost("host.example");
        producer.setPort(BigDecimal.valueOf(443));
        producer.setTls(true);
        producer.setIdpClientId(clientId);
        entityManager.persist(producer);
        return producer;
    }

    private Product persistProduct(Producer producer, String name) {
        ProductType topicType = entityManager
                .createQuery("SELECT t FROM ProductType t WHERE t.name = :name", ProductType.class)
                .setParameter("name", "topic")
                .getSingleResult();

        Product product = new Product();
        product.setName(name);
        product.setTopic(name + "-topic");
        product.setProducer(producer);
        product.setProductType(topicType);
        entityManager.persist(product);
        return product;
    }

    private Consumer persistConsumer(Organisation org, String name, String clientId) {
        Consumer consumer = new Consumer();
        consumer.setName(name);
        consumer.setScheduleType("cron");
        consumer.setOrg(org);
        consumer.setIdpClientId(clientId);
        entityManager.persist(consumer);
        return consumer;
    }

    private ProductConsumer persistSubscription(Product product, Consumer consumer) {
        ProductConsumer subscription = new ProductConsumer();
        subscription.setProduct(product);
        subscription.setConsumer(consumer);
        subscription.setGrantedTs(Timestamp.from(Instant.now()));
        subscription.setValidity(BigDecimal.ZERO);
        subscription.setScheduleType("cron");
        entityManager.persist(subscription);
        return subscription;
    }

    private void persistAttribute(String scopeCode, Long entityId, String attrName, String rawJsonValue) {
        AttributeDefinition definition = new AttributeDefinition();
        definition.setNamespace("policy");
        definition.setName(attrName);
        definition.setDescription("test");
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
        binding = attributeDefinitionScopeRepository.saveAndFlush(binding);

        AttributeValue value = new AttributeValue();
        value.setAttributeDefinitionScope(binding);
        value.setEntityId(entityId);
        value.setValue(rawJsonValue);
        value.setCreatedAt(Timestamp.from(Instant.now()));
        value.setCreatedBy("test");
        entityManager.persist(value);
    }

    @Test
    void producerConfig_carriesPolicyAttributesAcrossAllFourScopes_andEmptyForSiblingWithNone() {
        Organisation producerOrg = persistOrganisation("producer-org");
        Producer producer = persistProducer(producerOrg, "attributed-producer", "client-with-attrs");
        Product product = persistProduct(producer, "prod-1");

        Organisation consumerOrg = persistOrganisation("consumer-org");
        Consumer consumer = persistConsumer(consumerOrg, "attributed-consumer", "consumer-client");
        ProductConsumer subscription = persistSubscription(product, consumer);

        entityManager.flush();

        persistAttribute("PRODUCER", producer.getId(), "producer-tier", "\"gold\"");
        persistAttribute("CONSUMER", consumer.getId(), "consumer-tier", "\"silver\"");
        persistAttribute("ORGANISATION", consumerOrg.getId(), "org-region", "\"uk\"");
        persistAttribute("ORGANISATION", producerOrg.getId(), "producer-org-region", "\"north\"");
        persistAttribute("PRODUCT", product.getId(), "record-unit", "\"property\"");
        persistAttribute("SUBSCRIPTION", subscription.getId(), "sub-priority", "1");

        // A sibling producer with no attributes at all, for the empty-array assertion. It still
        // needs a product - getProducersByClientId inner-joins products, so a producer with none
        // would never appear in the response regardless of policy attributes.
        Producer bareProducer = persistProducer(producerOrg, "bare-producer", "client-no-attrs");
        persistProduct(bareProducer, "prod-2");
        entityManager.flush();
        // Force subsequent reads through the query layer's JOIN FETCHes rather than reusing the
        // self-constructed, association-less entity instances already sitting in this session's
        // first-level cache.
        entityManager.clear();

        ProducerConfigDTO cfg =
                configurationProvider().getProducerConfigByClientId("client-with-attrs", Optional.empty());

        ProducerDTO producerDto = cfg.getProducers().get(0);
        assertThat(producerDto.getPolicyAttributes())
                .extracting(PolicyAttributeDTO::getNamespace, PolicyAttributeDTO::getName, PolicyAttributeDTO::getValue)
                .containsExactly(tuple("policy", "producer-tier", "gold"));

        assertThat(producerDto.getOrganisation()).isNotNull();
        assertThat(producerDto.getOrganisation().getName()).isEqualTo("producer-org");
        assertThat(producerDto.getOrganisation().getKey()).isEqualTo("PRODUCER_ORG");

        assertThat(cfg.getOrganisation()).isNotNull();
        assertThat(cfg.getOrganisation().getName()).isEqualTo("producer-org");
        assertThat(cfg.getOrganisation().getKey()).isEqualTo("PRODUCER_ORG");
        assertThat(cfg.getOrganisation().getPolicyAttributes())
                .extracting(PolicyAttributeDTO::getNamespace, PolicyAttributeDTO::getName, PolicyAttributeDTO::getValue)
                .containsExactly(tuple("policy", "producer-org-region", "north"));

        ProductDTO productDto = producerDto.getProducts().get(0);
        assertThat(productDto.getPolicyAttributes())
                .extracting(PolicyAttributeDTO::getNamespace, PolicyAttributeDTO::getName, PolicyAttributeDTO::getValue)
                .containsExactly(tuple("policy", "record-unit", "property"));
        ConsumerDTO consumerDto = productDto.getConsumers().get(0);
        assertThat(consumerDto.getPolicyAttributes())
                .extracting(PolicyAttributeDTO::getNamespace, PolicyAttributeDTO::getName, PolicyAttributeDTO::getValue)
                .containsExactly(tuple("policy", "consumer-tier", "silver"));
        assertThat(consumerDto.getOrganisation().getName()).isEqualTo("consumer-org");
        assertThat(consumerDto.getOrganisation().getKey()).isEqualTo("CONSUMER_ORG");
        assertThat(consumerDto.getOrganisation().getPolicyAttributes())
                .extracting(PolicyAttributeDTO::getNamespace, PolicyAttributeDTO::getName, PolicyAttributeDTO::getValue)
                .containsExactly(tuple("policy", "org-region", "uk"));

        ProductConsumerDTO subscriptionDto = productDto.getConfigurations().get(0);
        assertThat(subscriptionDto.getPolicyAttributes())
                .extracting(PolicyAttributeDTO::getNamespace, PolicyAttributeDTO::getName, PolicyAttributeDTO::getValue)
                .containsExactly(tuple("policy", "sub-priority", "1"));

        ProducerConfigDTO bareCfg =
                configurationProvider().getProducerConfigByClientId("client-no-attrs", Optional.empty());
        assertThat(bareCfg.getProducers().get(0).getPolicyAttributes()).isEmpty();
    }
}
