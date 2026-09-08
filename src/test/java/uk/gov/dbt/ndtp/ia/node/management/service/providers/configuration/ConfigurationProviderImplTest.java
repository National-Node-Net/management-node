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

    @InjectMocks
    private ConfigurationProviderImpl configurationProvider;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        configurationProvider = new ConfigurationProviderImpl(
                consumerService, productConsumerService, producerService, certificateValidationProvider);
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
}
