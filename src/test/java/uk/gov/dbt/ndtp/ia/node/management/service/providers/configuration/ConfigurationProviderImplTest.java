/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.*;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ConsumerService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.OrganisationService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeScope;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProducerService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductConsumerService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.CertificateValidationProvider;

class ConfigurationProviderImplTest {

    @Mock
    private ConsumerService consumerService;

    @Mock
    private ProductConsumerService productConsumerService;

    @Mock
    private ProducerService producerService;

    @Mock
    private CertificateValidationProvider certificateValidationProvider;

    @Mock
    private PolicyAttributeService policyAttributeService;

    @Mock
    private OrganisationService organisationService;

    @InjectMocks
    private ConfigurationProviderImpl configurationProvider;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        configurationProvider = new ConfigurationProviderImpl(
                consumerService,
                productConsumerService,
                producerService,
                certificateValidationProvider,
                policyAttributeService,
                organisationService);
        // Default: treat all orgs as having active certificates, override in specific
        // tests to simulate inactive/missing certs.
        when(certificateValidationProvider.findActiveOrganisationIds(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids != null ? new HashSet<>(ids) : Set.of();
        });
    }

    private ConsumerDTO consumer(
            long id, String clientId, String name, String scheduleType, String scheduleExpression) {
        ConsumerDTO dto = ConsumerDTO.builder()
                .idpClientId(clientId)
                .name(name)
                .orgId(1L)
                .scheduleType(scheduleType)
                .scheduleExpression(scheduleExpression)
                .build();
        dto.setId(id);
        return dto;
    }

    private ProducerDTO producer(long id, boolean active, ProductDTO... products) {
        ProducerDTO p = ProducerDTO.builder()
                .id(id)
                .active(active)
                .orgId(1L)
                .idpClientId("cid")
                .name("p")
                .build();
        for (ProductDTO pr : products) {
            p.getProducts().add(pr);
        }
        return p;
    }

    private ProductDTO product(Long id, String name) {
        ProductDTO d = ProductDTO.builder().name(name).build();
        d.setId(id);
        return d;
    }

    private ProductConsumerDTO productConsumer(
            long productId, long consumerId, BigDecimal validityDays, Instant grantedAt) {
        return ProductConsumerDTO.builder()
                .productId(productId)
                .consumerId(consumerId)
                .validity(validityDays)
                .grantedTs(grantedAt != null ? Timestamp.from(grantedAt) : null)
                .scheduleType("CRON")
                .scheduleExpression("0 0 * * * *")
                .destination("topic")
                .build();
    }

    @Test
    void getConsumerConfigByClientId_filtersInactiveProducers_andProductsByValidIds_andSetsConfigs() {
        String clientId = "clientA";
        ConsumerDTO c1 = consumer(1L, clientId, "c1", "CRON", "@hourly");
        when(consumerService.findByIdpClientId(clientId)).thenReturn(List.of(c1));

        // Valid configs for product 100 only (null validity treated as valid)
        ProductConsumerDTO pc1 = productConsumer(100L, 1L, null, null);
        ProductConsumerDTO pc2 = productConsumer(100L, 1L, BigDecimal.ZERO, null);
        when(productConsumerService.findByConsumerId(1L)).thenReturn(List.of(pc1, pc2));

        // One active and one inactive producer; active has products 100 (kept) and 102 (removed)
        ProducerDTO active = producer(10L, true, product(100L, "dp-100"), product(102L, "dp-102"));
        ProducerDTO inactive = producer(11L, false, product(100L, "dp-100"));
        when(producerService.getProducersByConsumerIds(List.of(1L))).thenReturn(List.of(active, inactive));

        ConsumerConfigDTO cfg = configurationProvider.getConsumerConfigByClientId(clientId, Optional.empty());

        // Only active producer remains
        assertThat(cfg.getProducers()).containsExactly(active);
        // Products filtered to valid productIds (only 100)
        assertThat(active.getProducts()).extracting(ProductDTO::getId).containsExactly(100L);
        // Configurations set on product 100
        assertThat(active.getProducts().get(0).getConfigurations()).containsExactlyInAnyOrder(pc1, pc2);
        // Schedule and name propagated from first consumer
        assertThat(cfg.getScheduleType()).isEqualTo("CRON");
        assertThat(cfg.getScheduleExpression()).isEqualTo("@hourly");
        assertThat(cfg.getClientId()).isEqualTo(clientId);
    }

    @Test
    void getConsumerConfigByClientId_whenNoValidProducts_clearsAllProducerProducts() {
        String clientId = "clientB";
        ConsumerDTO c1 = consumer(2L, clientId, "c2", "FIXED", "PT10M");
        when(consumerService.findByIdpClientId(clientId)).thenReturn(List.of(c1));

        // No valid product-consumers returned
        when(productConsumerService.findByConsumerId(2L)).thenReturn(List.of());

        ProducerDTO active = producer(20L, true, product(200L, "dp-200"), product(201L, "dp-201"));
        when(producerService.getProducersByConsumerIds(List.of(2L))).thenReturn(List.of(active));

        ConsumerConfigDTO cfg = configurationProvider.getConsumerConfigByClientId(clientId, Optional.empty());

        assertThat(cfg.getProducers()).hasSize(1);
        assertThat(cfg.getProducers().get(0).getProducts()).isEmpty();
    }

    @Test
    void getConsumerConfigByClientId_withConsumerIdFilter_appliesFilter_andRemovesNullProductIds() {
        String clientId = "clientC";
        ConsumerDTO c1 = consumer(3L, clientId, "c3", "CRON", "@daily");
        when(consumerService.findByIdpClientId(clientId)).thenReturn(List.of(c1));

        ProductConsumerDTO pc = productConsumer(300L, 3L, null, null);
        when(productConsumerService.findByConsumerId(3L)).thenReturn(List.of(pc));

        ProductDTO pNull = product(null, "no-id");
        ProductDTO pKept = product(300L, "ok");
        ProducerDTO active = producer(30L, true, pNull, pKept);
        when(producerService.getProducersByConsumerIds(List.of(3L))).thenReturn(List.of(active));

        ConsumerConfigDTO cfg = configurationProvider.getConsumerConfigByClientId(clientId, Optional.of(3L));

        // Only products with ids in valid set are kept => null removed, only 300 remains
        assertThat(cfg.getProducers().get(0).getProducts())
                .extracting(ProductDTO::getId)
                .containsExactly(300L);
        // And configurations attached to remaining product
        assertThat(cfg.getProducers().get(0).getProducts().get(0).getConfigurations())
                .containsExactly(pc);
    }

    @Test
    void getProducerConfigByClientId_onlyActiveProducers_kept_andOnlyValidConsumersAdded() {
        String clientId = "clientP";
        ProductDTO pr1 = product(900L, "prov1");
        ProductDTO pr2 = product(901L, "prov2");
        ProducerDTO active = producer(91L, true, pr1, pr2);
        ProducerDTO inactive = producer(92L, false, product(902L, "prov3"));

        when(producerService.getProducersByClientId(clientId)).thenReturn(List.of(active, inactive));

        // product ids should be collected and passed to consumerService.getConsumersOfProviders
        when(consumerService.getConsumersOfProviders(any())).thenReturn(Map.of());

        // For pr1: one valid consumer-provider (validity 10 days from now) and one invalid (expired)
        ProductConsumerDTO validCP = productConsumer(900L, 501L, BigDecimal.TEN, Instant.now());
        ProductConsumerDTO expiredCP =
                productConsumer(900L, 502L, BigDecimal.ONE, Instant.now().minusSeconds(86400 * 5));
        when(productConsumerService.findByDataProviderId(900L)).thenReturn(List.of(validCP, expiredCP));
        when(productConsumerService.findByDataProviderId(901L)).thenReturn(List.of());

        // Resolve consumer lookups
        ConsumerDTO c501 = consumer(501L, "cid501", "c501", "CRON", "@hourly");
        when(consumerService.findById(501L)).thenReturn(Optional.of(c501));
        when(consumerService.findById(502L)).thenReturn(Optional.empty());

        ProducerConfigDTO cfg = configurationProvider.getProducerConfigByClientId(clientId, Optional.empty());

        // Only active producer present
        assertThat(cfg.getProducers()).containsExactly(active);

        // Verify consumersOfProviders called with both product ids
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(consumerService).getConsumersOfProviders(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(900L, 901L);

        // For pr1, only valid consumer added
        assertThat(pr1.getConsumers()).containsExactly(c501);
        // pr2 has none
        assertThat(pr2.getConsumers()).isEmpty();
    }

    @Test
    void getProducerConfigByClientId_whenNoProducersFound_returnsEmptyConfig() {
        String clientId = "nonExistentClient";
        when(producerService.getProducersByClientId(clientId)).thenReturn(List.of());

        ProducerConfigDTO cfg = configurationProvider.getProducerConfigByClientId(clientId, Optional.empty());

        assertThat(cfg).isNotNull();
        assertThat(cfg.getClientId()).isEqualTo(clientId);
        assertThat(cfg.getProducers()).isEmpty();
    }

    @Test
    void getConsumerConfigByClientId_whenNoConsumersFound_returnsEmptyConfig() {
        String clientId = "nonExistentClient";
        when(consumerService.findByIdpClientId(clientId)).thenReturn(List.of());

        ConsumerConfigDTO cfg = configurationProvider.getConsumerConfigByClientId(clientId, Optional.empty());

        assertThat(cfg).isNotNull();
        assertThat(cfg.getClientId()).isEqualTo(clientId);
        assertThat(cfg.getProducers()).isEmpty();
        assertThat(cfg.getName()).isNull();
        assertThat(cfg.getScheduleType()).isNull();
        assertThat(cfg.getScheduleExpression()).isNull();
    }

    @Test
    void getProducerConfigByClientId_withValidValidity_includesConsumer() {
        String clientId = "producerClient";
        ProductDTO p1 = product(100L, "p1");
        ProducerDTO pr1 = producer(1L, true, p1);
        when(producerService.getProducersByClientId(clientId)).thenReturn(List.of(pr1));

        // Consumer with valid validity
        ProductConsumerDTO pc1 =
                productConsumer(100L, 1L, new BigDecimal("20"), Instant.now().minusSeconds(86400 * 10));
        when(productConsumerService.findByDataProviderId(100L)).thenReturn(List.of(pc1));
        when(consumerService.findById(1L)).thenReturn(Optional.of(consumer(1L, "c1", "c1", null, null)));

        ProducerConfigDTO cfg = configurationProvider.getProducerConfigByClientId(clientId, Optional.empty());

        assertThat(cfg.getProducers().get(0).getProducts().get(0).getConsumers())
                .hasSize(1);
    }

    @Test
    void getConsumerConfigByClientId_withConsumerId_filtersByConsumerId() {
        String clientId = "clientA";
        ConsumerDTO c1 = consumer(1L, clientId, "c1", "CRON", "@hourly");

        when(consumerService.findByIdpClientId(clientId)).thenReturn(List.of(c1));

        ConsumerConfigDTO cfg = configurationProvider.getConsumerConfigByClientId(clientId, Optional.of(1L));

        assertThat(cfg.getName()).isEqualTo("c1");
    }

    @Test
    void getProducerConfigByClientId_withProducerId_filtersByProducerId() {
        String clientId = "producerClient";
        ProductDTO p1 = product(100L, "p1");
        ProducerDTO pr1 = producer(1L, true, p1);

        when(producerService.getProducersByClientId(clientId)).thenReturn(List.of(pr1));

        ProducerConfigDTO cfg = configurationProvider.getProducerConfigByClientId(clientId, Optional.of(1L));

        assertThat(cfg.getProducers()).hasSize(1);
        assertThat(cfg.getProducers().get(0).getId()).isEqualTo(1L);
    }

    @Test
    void getProducerConfigByClientId_withInvalidValidity_filtersOutConsumer() {
        String clientId = "producerClient";
        ProductDTO p1 = product(100L, "p1");
        ProducerDTO pr1 = producer(1L, true, p1);
        when(producerService.getProducersByClientId(clientId)).thenReturn(List.of(pr1));

        // Consumer with expired validity
        ProductConsumerDTO pc1 =
                productConsumer(100L, 1L, new BigDecimal("5"), Instant.now().minusSeconds(86400 * 10));
        when(productConsumerService.findByDataProviderId(100L)).thenReturn(List.of(pc1));

        ProducerConfigDTO cfg = configurationProvider.getProducerConfigByClientId(clientId, Optional.empty());

        assertThat(cfg.getProducers().get(0).getProducts().get(0).getConsumers())
                .isEmpty();
    }

    @Test
    void getConsumerConfig_filtersOutProducersWithInactiveCerts() {
        String clientId = "clientA";
        ConsumerDTO c1 = consumer(1L, clientId, "c1", "CRON", "@hourly");
        when(consumerService.findByIdpClientId(clientId)).thenReturn(List.of(c1));

        ProductConsumerDTO pc = productConsumer(100L, 1L, null, null);
        when(productConsumerService.findByConsumerId(1L)).thenReturn(List.of(pc));

        // Two active producers with different orgIds
        ProducerDTO activeOrgProducer = producer(10L, true, product(100L, "dp-100"));
        activeOrgProducer.setOrgId(100L);
        ProducerDTO inactiveOrgProducer = producer(11L, true, product(100L, "dp-100"));
        inactiveOrgProducer.setOrgId(200L);
        when(producerService.getProducersByConsumerIds(List.of(1L)))
                .thenReturn(List.of(activeOrgProducer, inactiveOrgProducer));

        // Only org 100 has an active certificate
        when(certificateValidationProvider.findActiveOrganisationIds(Set.of(100L, 200L)))
                .thenReturn(Set.of(100L));

        ConsumerConfigDTO cfg = configurationProvider.getConsumerConfigByClientId(clientId, Optional.empty());

        assertThat(cfg.getProducers()).containsExactly(activeOrgProducer);
    }

    @Test
    void getProducerConfig_filtersOutConsumersWithInactiveCerts() {
        String clientId = "clientP";
        ProductDTO p1 = product(900L, "prov1");
        ProducerDTO pr1 = producer(91L, true, p1);
        when(producerService.getProducersByClientId(clientId)).thenReturn(List.of(pr1));
        when(consumerService.getConsumersOfProviders(any())).thenReturn(Map.of());

        ProductConsumerDTO cp1 = productConsumer(900L, 501L, null, null);
        ProductConsumerDTO cp2 = productConsumer(900L, 502L, null, null);
        when(productConsumerService.findByDataProviderId(900L)).thenReturn(List.of(cp1, cp2));

        ConsumerDTO activeOrgConsumer = consumer(501L, "cid501", "c501", "CRON", "@hourly");
        activeOrgConsumer.setOrgId(300L);
        ConsumerDTO inactiveOrgConsumer = consumer(502L, "cid502", "c502", "CRON", "@hourly");
        inactiveOrgConsumer.setOrgId(400L);
        when(consumerService.findById(501L)).thenReturn(Optional.of(activeOrgConsumer));
        when(consumerService.findById(502L)).thenReturn(Optional.of(inactiveOrgConsumer));

        // Only org 300 has an active certificate
        when(certificateValidationProvider.findActiveOrganisationIds(Set.of(300L, 400L)))
                .thenReturn(Set.of(300L));

        ProducerConfigDTO cfg = configurationProvider.getProducerConfigByClientId(clientId, Optional.empty());

        assertThat(cfg.getProducers().get(0).getProducts().get(0).getConsumers())
                .containsExactly(activeOrgConsumer);
    }

    // DPAV-3162: policy attribute wiring

    @Test
    void getConsumerConfigByClientId_namesTheProducersOrganisationButWithoutItsPolicyAttributes() {
        String clientId = "consumerClient";
        ConsumerDTO consumer = consumer(500L, clientId, "c500", "CRON", "@daily");
        when(consumerService.findByIdpClientId(clientId)).thenReturn(List.of(consumer));

        ProductConsumerDTO subscription = productConsumer(700L, 500L, null, null);
        subscription.setId(9100L);
        when(productConsumerService.findByConsumerId(500L)).thenReturn(List.of(subscription));

        ProductDTO product = product(700L, "prod");
        ProducerDTO producer = producer(80L, true, product);
        when(producerService.getProducersByConsumerIds(List.of(500L))).thenReturn(List.of(producer));
        when(organisationService.findByIds(any()))
                .thenReturn(Map.of(
                        1L,
                        OrganisationDTO.builder()
                                .name("Producer Org")
                                .key("PROD_ORG")
                                .build()));

        ConsumerConfigDTO cfg = configurationProvider.getConsumerConfigByClientId(clientId, Optional.empty());

        ProducerDTO returned = cfg.getProducers().get(0);
        // the consumer is told which organisation publishes to it...
        assertThat(returned.getOrganisation()).isNotNull();
        assertThat(returned.getOrganisation().getName()).isEqualTo("Producer Org");
        assertThat(returned.getOrganisation().getKey()).isEqualTo("PROD_ORG");
        // ...but never what that organisation is entitled to hold
        assertThat(returned.getOrganisation().getPolicyAttributes()).isEmpty();
        assertThat(returned.getPolicyAttributes()).isEmpty();
        assertThat(returned.getProducts().get(0).getPolicyAttributes()).isEmpty();
        // no policy attribute lookup happens at all on this path
        verifyNoInteractions(policyAttributeService);
    }

    @Test
    void getProducerConfigByClientId_populatesPolicyAttributesForEveryScope() {
        String clientId = "policyClient";
        ProductDTO product = product(700L, "prod");
        ProducerDTO producer = producer(70L, true, product);
        when(producerService.getProducersByClientId(clientId)).thenReturn(List.of(producer));
        when(consumerService.getConsumersOfProviders(any())).thenReturn(Map.of());

        ProductConsumerDTO subscription = productConsumer(700L, 701L, null, null);
        subscription.setId(9001L);
        when(productConsumerService.findByDataProviderId(700L)).thenReturn(List.of(subscription));

        ConsumerDTO consumer = consumer(701L, "cid701", "c701", "CRON", "@hourly");
        consumer.setOrgId(801L);
        when(consumerService.findById(701L)).thenReturn(Optional.of(consumer));

        PolicyAttributeDTO producerAttr = PolicyAttributeDTO.builder()
                .namespace("policy")
                .name("a")
                .value("1")
                .build();
        PolicyAttributeDTO consumerAttr = PolicyAttributeDTO.builder()
                .namespace("policy")
                .name("b")
                .value("2")
                .build();
        PolicyAttributeDTO orgAttr = PolicyAttributeDTO.builder()
                .namespace("policy")
                .name("c")
                .value("3")
                .build();
        PolicyAttributeDTO subscriptionAttr = PolicyAttributeDTO.builder()
                .namespace("policy")
                .name("d")
                .value("4")
                .build();
        PolicyAttributeDTO productAttr = PolicyAttributeDTO.builder()
                .namespace("policy")
                .name("e")
                .value("5")
                .build();
        // the producer's own organisation (id 1) - distinct from the consumer's organisation (801),
        // so the assertions below prove which one the config-level organisation reports
        PolicyAttributeDTO producerOrgAttr = PolicyAttributeDTO.builder()
                .namespace("policy")
                .name("f")
                .value("6")
                .build();
        when(policyAttributeService.findAttributes(70L, PolicyAttributeScope.PRODUCER))
                .thenReturn(List.of(producerAttr));
        when(policyAttributeService.findAttributes(701L, PolicyAttributeScope.CONSUMER))
                .thenReturn(List.of(consumerAttr));
        when(policyAttributeService.findAttributes(801L, PolicyAttributeScope.ORGANISATION))
                .thenReturn(List.of(orgAttr));
        when(policyAttributeService.findAttributes(9001L, PolicyAttributeScope.SUBSCRIPTION))
                .thenReturn(List.of(subscriptionAttr));
        when(policyAttributeService.findAttributes(700L, PolicyAttributeScope.PRODUCT))
                .thenReturn(List.of(productAttr));
        when(policyAttributeService.findAttributes(1L, PolicyAttributeScope.ORGANISATION))
                .thenReturn(List.of(producerOrgAttr));
        when(organisationService.findByIds(any()))
                .thenReturn(Map.of(
                        1L,
                        OrganisationDTO.builder()
                                .name("Producer Org")
                                .key("PROD_ORG")
                                .build(),
                        801L,
                        OrganisationDTO.builder()
                                .name("Consumer Org")
                                .key("CONS_ORG")
                                .build()));

        ProducerConfigDTO cfg = configurationProvider.getProducerConfigByClientId(clientId, Optional.empty());

        ProducerDTO returnedProducer = cfg.getProducers().get(0);
        assertThat(returnedProducer.getPolicyAttributes()).containsExactly(producerAttr);

        ConsumerDTO returnedConsumer =
                returnedProducer.getProducts().get(0).getConsumers().get(0);
        assertThat(returnedConsumer.getPolicyAttributes()).containsExactly(consumerAttr);
        assertThat(returnedConsumer.getOrganisation()).isNotNull();
        assertThat(returnedConsumer.getOrganisation().getPolicyAttributes()).containsExactly(orgAttr);

        ProductConsumerDTO returnedSubscription =
                returnedProducer.getProducts().get(0).getConfigurations().get(0);
        assertThat(returnedSubscription.getPolicyAttributes()).containsExactly(subscriptionAttr);

        assertThat(returnedProducer.getProducts().get(0).getPolicyAttributes()).containsExactly(productAttr);

        // the response itself reports the organisation its producers belong to
        assertThat(cfg.getOrganisation()).isNotNull();
        assertThat(cfg.getOrganisation().getKey()).isEqualTo("PROD_ORG");
        assertThat(cfg.getOrganisation().getName()).isEqualTo("Producer Org");
        assertThat(cfg.getOrganisation().getPolicyAttributes()).containsExactly(producerOrgAttr);
        // and the producer carries the same organisation as the response header
        assertThat(returnedProducer.getOrganisation().getKey()).isEqualTo("PROD_ORG");
    }

    @Test
    void getProducerConfigByClientId_configOrganisationIsNullWhenNoProducerResolvesOne() {
        String clientId = "noConfigOrgClient";
        ProductDTO product = product(700L, "prod");
        ProducerDTO producer = producer(75L, true, product);
        when(producerService.getProducersByClientId(clientId)).thenReturn(List.of(producer));
        when(consumerService.getConsumersOfProviders(any())).thenReturn(Map.of());
        when(productConsumerService.findByDataProviderId(700L)).thenReturn(List.of());
        when(organisationService.findByIds(any())).thenReturn(Map.of());

        ProducerConfigDTO cfg = configurationProvider.getProducerConfigByClientId(clientId, Optional.empty());

        assertThat(cfg.getOrganisation()).isNull();
    }

    @Test
    void getProducerConfigByClientId_producersSpanningTwoOrganisations_reportsTheFirstAndWarns() {
        String clientId = "multiOrgClient";
        ProducerDTO first = producer(77L, true, product(700L, "prodA"));
        ProducerDTO second = producer(78L, true, product(701L, "prodB"));
        second.setOrgId(2L);
        when(producerService.getProducersByClientId(clientId)).thenReturn(List.of(first, second));
        when(consumerService.getConsumersOfProviders(any())).thenReturn(Map.of());
        when(productConsumerService.findByDataProviderId(any())).thenReturn(List.of());
        when(organisationService.findByIds(any()))
                .thenReturn(Map.of(
                        1L,
                        OrganisationDTO.builder().name("First Org").key("FIRST").build(),
                        2L,
                        OrganisationDTO.builder()
                                .name("Second Org")
                                .key("SECOND")
                                .build()));

        ProducerConfigDTO cfg = configurationProvider.getProducerConfigByClientId(clientId, Optional.empty());

        assertThat(cfg.getOrganisation().getKey()).isEqualTo("FIRST");
        // each producer still reports its own organisation
        assertThat(cfg.getProducers())
                .extracting(p -> p.getOrganisation().getKey())
                .containsExactly("FIRST", "SECOND");
    }

    @Test
    void getProducerConfigByClientId_configOrganisationIsACopyNotTheProducersInstance() {
        String clientId = "copyOrgClient";
        ProductDTO product = product(700L, "prod");
        ProducerDTO producer = producer(76L, true, product);
        when(producerService.getProducersByClientId(clientId)).thenReturn(List.of(producer));
        when(consumerService.getConsumersOfProviders(any())).thenReturn(Map.of());
        when(productConsumerService.findByDataProviderId(700L)).thenReturn(List.of());
        when(organisationService.findByIds(any()))
                .thenReturn(Map.of(
                        1L,
                        OrganisationDTO.builder()
                                .name("Producer Org")
                                .key("PROD_ORG")
                                .build()));

        ProducerConfigDTO cfg = configurationProvider.getProducerConfigByClientId(clientId, Optional.empty());

        assertThat(cfg.getOrganisation()).isNotSameAs(cfg.getProducers().get(0).getOrganisation());
        assertThat(cfg.getOrganisation().getKey())
                .isEqualTo(cfg.getProducers().get(0).getOrganisation().getKey());
    }

    @Test
    void getProducerConfigByClientId_leavesOrganisationNullWhenNothingHasAnOrgId() {
        String clientId = "noOrgClient";
        ProductDTO product = product(700L, "prod");
        ProducerDTO producer = ProducerDTO.builder()
                .id(72L)
                .active(true)
                .idpClientId("cid")
                .name("p")
                .build();
        producer.getProducts().add(product);
        when(producerService.getProducersByClientId(clientId)).thenReturn(List.of(producer));
        when(consumerService.getConsumersOfProviders(any())).thenReturn(Map.of());
        when(productConsumerService.findByDataProviderId(700L)).thenReturn(List.of());

        ProducerConfigDTO cfg = configurationProvider.getProducerConfigByClientId(clientId, Optional.empty());

        assertThat(cfg.getProducers().get(0).getOrganisation()).isNull();
        // nothing to look up, so the organisation lookup is skipped entirely
        verify(organisationService, never()).findByIds(any());
    }

    @Test
    void getProducerConfigByClientId_leavesOrganisationNullWhenTheOrgIdResolvesToNothing() {
        String clientId = "orphanOrgClient";
        ProductDTO product = product(700L, "prod");
        ProducerDTO producer = producer(73L, true, product);
        when(producerService.getProducersByClientId(clientId)).thenReturn(List.of(producer));
        when(consumerService.getConsumersOfProviders(any())).thenReturn(Map.of());
        when(productConsumerService.findByDataProviderId(700L)).thenReturn(List.of());
        // producer.orgId is 1L, but no organisation row comes back for it
        when(organisationService.findByIds(any())).thenReturn(Map.of());

        ProducerConfigDTO cfg = configurationProvider.getProducerConfigByClientId(clientId, Optional.empty());

        assertThat(cfg.getProducers().get(0).getOrganisation()).isNull();
    }

    @Test
    void getProducerConfigByClientId_consumerWithNoOrgId_getsNullOrganisation() {
        String clientId = "consumerNoOrgClient";
        ProductDTO product = product(700L, "prod");
        ProducerDTO producer = producer(74L, true, product);
        when(producerService.getProducersByClientId(clientId)).thenReturn(List.of(producer));
        when(consumerService.getConsumersOfProviders(any())).thenReturn(Map.of());

        ProductConsumerDTO subscription = productConsumer(700L, 705L, null, null);
        subscription.setId(9005L);
        when(productConsumerService.findByDataProviderId(700L)).thenReturn(List.of(subscription));

        ConsumerDTO consumer = ConsumerDTO.builder().name("c705").build();
        consumer.setId(705L);
        when(consumerService.findById(705L)).thenReturn(Optional.of(consumer));
        when(organisationService.findByIds(any()))
                .thenReturn(Map.of(
                        1L,
                        OrganisationDTO.builder()
                                .name("Producer Org")
                                .key("PROD_ORG")
                                .build()));

        ProducerConfigDTO cfg = configurationProvider.getProducerConfigByClientId(clientId, Optional.empty());

        ConsumerDTO returned =
                cfg.getProducers().get(0).getProducts().get(0).getConsumers().get(0);
        assertThat(returned.getOrganisation()).isNull();
        assertThat(cfg.getProducers().get(0).getOrganisation().getKey()).isEqualTo("PROD_ORG");
    }

    @Test
    void getProducerConfigByClientId_producerWithNoAttributes_getsEmptyPolicyAttributesList() {
        String clientId = "noAttrClient";
        ProducerDTO producer = producer(71L, true);
        when(producerService.getProducersByClientId(clientId)).thenReturn(List.of(producer));
        when(consumerService.getConsumersOfProviders(any())).thenReturn(Map.of());
        when(policyAttributeService.findAttributes(71L, PolicyAttributeScope.PRODUCER))
                .thenReturn(List.of());

        ProducerConfigDTO cfg = configurationProvider.getProducerConfigByClientId(clientId, Optional.empty());

        assertThat(cfg.getProducers().get(0).getPolicyAttributes()).isEmpty();
    }

    @Test
    void getConsumerConfigByClientId_neverCallsPolicyAttributeService() {
        String clientId = "clientA";
        ConsumerDTO c1 = consumer(1L, clientId, "c1", "CRON", "@hourly");
        when(consumerService.findByIdpClientId(clientId)).thenReturn(List.of(c1));
        when(productConsumerService.findByConsumerId(1L)).thenReturn(List.of());
        when(producerService.getProducersByConsumerIds(List.of(1L))).thenReturn(List.of());

        configurationProvider.getConsumerConfigByClientId(clientId, Optional.empty());

        verifyNoInteractions(policyAttributeService);
    }

    @Test
    void getProducerConfigByClientId_producerWithNoOrgId_getsNullOrganisationWhileItsConsumersResolveTheirs() {
        String clientId = "producerNoOrgClient";
        ProductDTO product = product(700L, "prod");
        // no orgId on the producer, unlike the producer(..) helper
        ProducerDTO producer = ProducerDTO.builder()
                .id(75L)
                .active(true)
                .idpClientId("cid")
                .name("p")
                .build();
        producer.getProducts().add(product);
        when(producerService.getProducersByClientId(clientId)).thenReturn(List.of(producer));
        when(consumerService.getConsumersOfProviders(any())).thenReturn(Map.of());

        ProductConsumerDTO subscription = productConsumer(700L, 706L, null, null);
        subscription.setId(9006L);
        when(productConsumerService.findByDataProviderId(700L)).thenReturn(List.of(subscription));

        ConsumerDTO consumer = ConsumerDTO.builder().name("c706").orgId(801L).build();
        consumer.setId(706L);
        when(consumerService.findById(706L)).thenReturn(Optional.of(consumer));
        when(organisationService.findByIds(any()))
                .thenReturn(Map.of(
                        801L,
                        OrganisationDTO.builder()
                                .name("Consumer Org")
                                .key("CONS_ORG")
                                .build()));

        ProducerConfigDTO cfg = configurationProvider.getProducerConfigByClientId(clientId, Optional.empty());

        ProducerDTO returnedProducer = cfg.getProducers().get(0);
        // the lookup does happen - a consumer needs it - but the producer has nothing to resolve
        verify(organisationService).findByIds(Set.of(801L));
        assertThat(returnedProducer.getOrganisation()).isNull();
        assertThat(cfg.getOrganisation()).isNull();
        assertThat(returnedProducer.getProducts().get(0).getConsumers().get(0).getOrganisation())
                .isNotNull();
        assertThat(returnedProducer
                        .getProducts()
                        .get(0)
                        .getConsumers()
                        .get(0)
                        .getOrganisation()
                        .getKey())
                .isEqualTo("CONS_ORG");
    }

    @Test
    void getProducerConfigByClientId_organisationSharedByTwoConsumers_isResolvedOnceButNotSharedByReference() {
        String clientId = "sharedOrgClient";
        ProductDTO productA = product(700L, "prodA");
        ProductDTO productB = product(701L, "prodB");
        ProducerDTO producer = producer(76L, true, productA, productB);
        when(producerService.getProducersByClientId(clientId)).thenReturn(List.of(producer));
        when(consumerService.getConsumersOfProviders(any())).thenReturn(Map.of());

        ProductConsumerDTO subscriptionA = productConsumer(700L, 707L, null, null);
        subscriptionA.setId(9007L);
        ProductConsumerDTO subscriptionB = productConsumer(701L, 708L, null, null);
        subscriptionB.setId(9008L);
        when(productConsumerService.findByDataProviderId(700L)).thenReturn(List.of(subscriptionA));
        when(productConsumerService.findByDataProviderId(701L)).thenReturn(List.of(subscriptionB));

        // two different consumers under two different products, both in organisation 801
        ConsumerDTO consumerA = ConsumerDTO.builder().name("c707").orgId(801L).build();
        consumerA.setId(707L);
        ConsumerDTO consumerB = ConsumerDTO.builder().name("c708").orgId(801L).build();
        consumerB.setId(708L);
        when(consumerService.findById(707L)).thenReturn(Optional.of(consumerA));
        when(consumerService.findById(708L)).thenReturn(Optional.of(consumerB));

        PolicyAttributeDTO orgAttr = PolicyAttributeDTO.builder()
                .name("classification")
                .value("OFFICIAL")
                .build();
        when(policyAttributeService.findAttributes(801L, PolicyAttributeScope.ORGANISATION))
                .thenReturn(List.of(orgAttr));
        when(organisationService.findByIds(any()))
                .thenReturn(Map.of(
                        1L,
                        OrganisationDTO.builder()
                                .name("Producer Org")
                                .key("PROD_ORG")
                                .build(),
                        801L,
                        OrganisationDTO.builder()
                                .name("Consumer Org")
                                .key("CONS_ORG")
                                .build()));

        ProducerConfigDTO cfg = configurationProvider.getProducerConfigByClientId(clientId, Optional.empty());

        // the duplicate org id is collapsed before the lookup, and resolved exactly once
        verify(organisationService, times(1)).findByIds(Set.of(1L, 801L));
        verify(policyAttributeService, times(1)).findAttributes(801L, PolicyAttributeScope.ORGANISATION);

        ProducerDTO returnedProducer = cfg.getProducers().get(0);
        OrganisationDTO orgOfA =
                returnedProducer.getProducts().get(0).getConsumers().get(0).getOrganisation();
        OrganisationDTO orgOfB =
                returnedProducer.getProducts().get(1).getConsumers().get(0).getOrganisation();

        assertThat(orgOfA.getKey()).isEqualTo("CONS_ORG");
        assertThat(orgOfB.getKey()).isEqualTo("CONS_ORG");
        assertThat(orgOfA.getPolicyAttributes()).containsExactly(orgAttr);
        assertThat(orgOfB.getPolicyAttributes()).containsExactly(orgAttr);
        // each owner gets its own instance, so mutating one cannot leak into the other
        assertThat(orgOfA).isNotSameAs(orgOfB);
        assertThat(returnedProducer.getOrganisation()).isNotSameAs(orgOfA);
    }
}
