/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uk.gov.dbt.ndtp.ia.node.management.exception.SubscriptionRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.SubscriptionRejectedException.Reason;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductSubscriptionRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductSubscriptionResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductSubscriptionPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Consumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Product;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.organisation.Organisation;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ConsumerRepository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductConsumerRepository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.OrganisationService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;

/**
 * The subscription service: choosing the consumer, refusing what cannot be granted, and taking the
 * validity from the policy decision rather than inventing one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductSubscriptionServiceImplTest {

    private static final String ORG_KEY = "ENV";
    private static final long ORG_ID = 5L;
    private static final long PRODUCT_ID = 42L;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ConsumerRepository consumerRepository;

    @Mock
    private ProductConsumerRepository productConsumerRepository;

    @Mock
    private OrganisationService organisationService;

    @InjectMocks
    private ProductSubscriptionServiceImpl service;

    // ------------------------------------------------------------------ fixtures

    private static Organisation organisation(long id) {
        Organisation org = new Organisation();
        org.setId(id);
        org.setOrganisationKey(ORG_KEY);
        return org;
    }

    private static Consumer consumer(long id, String name, long orgId, boolean isDefault) {
        Consumer consumer = new Consumer();
        consumer.setId(id);
        consumer.setName(name);
        consumer.setOrg(organisation(orgId));
        consumer.setDefault(isDefault);
        return consumer;
    }

    private static Product product() {
        Product product = new Product();
        product.setId(PRODUCT_ID);
        product.setName("FloodRiskMapZones");
        return product;
    }

    private static ProductSubscriptionRequestDTO request(Long consumerId) {
        return ProductSubscriptionRequestDTO.builder()
                .productId(PRODUCT_ID)
                .consumerId(consumerId)
                .build();
    }

    private static Optional<PolicyDecision<ProductSubscriptionPolicyDecisionDetails>> decision(
            Boolean requiresApproval, Integer maxValidity, Integer validity) {
        return Optional.of(PolicyDecision.of(true, ProductSubscriptionPolicyDecisionDetails.class)
                .withDetails(new ProductSubscriptionPolicyDecisionDetails(
                        requiresApproval, maxValidity, List.of("cron", "interval"), validity)));
    }

    private void organisationExists() {
        when(organisationService.findIdByKey(ORG_KEY)).thenReturn(Optional.of(ORG_ID));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(productConsumerRepository.save(any())).thenAnswer(invocation -> {
            ProductConsumer saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });
    }

    // ------------------------------------------------------------------ choosing the consumer

    @Test
    void namedConsumerBelongingToTheOrganisation_isUsed() {
        organisationExists();
        when(consumerRepository.findById(4L)).thenReturn(Optional.of(consumer(4L, "consumer-a", ORG_ID, false)));

        ProductSubscriptionResponseDTO response = service.subscribe(request(4L), ORG_KEY, decision(false, 90, 90));

        assertThat(response.consumerId()).isEqualTo(4L);
        assertThat(response.consumerName()).isEqualTo("consumer-a");
    }

    /** An id must not be a way to subscribe another organisation's consumer. */
    @Test
    void namedConsumerOfAnotherOrganisation_isRefused() {
        organisationExists();
        when(consumerRepository.findById(4L)).thenReturn(Optional.of(consumer(4L, "someone-else", 999L, false)));

        assertThatThrownBy(() -> service.subscribe(request(4L), ORG_KEY, decision(false, 90, 90)))
                .isInstanceOf(SubscriptionRejectedException.class)
                .hasMessageContaining("does not belong to organisation")
                .extracting(e -> ((SubscriptionRejectedException) e).getReason())
                .isEqualTo(Reason.CONSUMER_NOT_OWNED);
        verify(productConsumerRepository, never()).save(any());
    }

    @Test
    void noConsumerNamed_usesTheDeclaredDefault() {
        organisationExists();
        when(consumerRepository.findByOrgIdAndIsDefaultTrue(ORG_ID))
                .thenReturn(Optional.of(consumer(8L, "the-default", ORG_ID, true)));

        ProductSubscriptionResponseDTO response = service.subscribe(request(null), ORG_KEY, decision(false, 90, 90));

        assertThat(response.consumerId()).isEqualTo(8L);
        verify(consumerRepository, never()).findByOrgId(any());
    }

    /** One consumer and no declared default is not ambiguous, so it is used. */
    @Test
    void noConsumerNamedAndNoDefault_usesTheOnlyConsumer() {
        organisationExists();
        when(consumerRepository.findByOrgIdAndIsDefaultTrue(ORG_ID)).thenReturn(Optional.empty());
        when(consumerRepository.findByOrgId(ORG_ID)).thenReturn(List.of(consumer(3L, "only-one", ORG_ID, false)));

        assertThat(service.subscribe(request(null), ORG_KEY, decision(false, 90, 90))
                        .consumerId())
                .isEqualTo(3L);
    }

    @Test
    void severalConsumersAndNoDefault_asksTheCallerToNameOne() {
        organisationExists();
        when(consumerRepository.findByOrgIdAndIsDefaultTrue(ORG_ID)).thenReturn(Optional.empty());
        when(consumerRepository.findByOrgId(ORG_ID))
                .thenReturn(List.of(consumer(1L, "a", ORG_ID, false), consumer(2L, "b", ORG_ID, false)));

        assertThatThrownBy(() -> service.subscribe(request(null), ORG_KEY, decision(false, 90, 90)))
                .isInstanceOf(SubscriptionRejectedException.class)
                .hasMessageContaining("Multiple consumers found, please specify consumer_id")
                .extracting(e -> ((SubscriptionRejectedException) e).getReason())
                .isEqualTo(Reason.AMBIGUOUS_CONSUMER);
    }

    @Test
    void organisationWithNoConsumerAtAll_isRefused() {
        organisationExists();
        when(consumerRepository.findByOrgIdAndIsDefaultTrue(ORG_ID)).thenReturn(Optional.empty());
        when(consumerRepository.findByOrgId(ORG_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.subscribe(request(null), ORG_KEY, decision(false, 90, 90)))
                .isInstanceOf(SubscriptionRejectedException.class)
                .extracting(e -> ((SubscriptionRejectedException) e).getReason())
                .isEqualTo(Reason.NO_CONSUMER);
    }

    // ------------------------------------------------------------------ what already exists

    @Test
    void consumerAlreadySubscribedToThatProduct_isRefusedWithAMessageNamingIt() {
        organisationExists();
        when(consumerRepository.findById(4L)).thenReturn(Optional.of(consumer(4L, "consumer-a", ORG_ID, false)));
        when(productConsumerRepository.existsByProductIdAndConsumerId(PRODUCT_ID, 4L))
                .thenReturn(true);

        assertThatThrownBy(() -> service.subscribe(request(4L), ORG_KEY, decision(false, 90, 90)))
                .isInstanceOf(SubscriptionRejectedException.class)
                .hasMessageContaining("already subscribed")
                .hasMessageContaining("consumer-a")
                .extracting(e -> ((SubscriptionRejectedException) e).getReason())
                .isEqualTo(Reason.ALREADY_SUBSCRIBED);
        verify(productConsumerRepository, never()).save(any());
    }

    /**
     * One organisation may hold the same product on several consumers. Only the pair is unique, so
     * a second consumer subscribing the same product is a normal request.
     */
    @Test
    void sameProductOnAnotherConsumerOfTheSameOrganisation_isAllowed() {
        organisationExists();
        when(consumerRepository.findById(5L)).thenReturn(Optional.of(consumer(5L, "consumer-b", ORG_ID, false)));
        when(productConsumerRepository.existsByProductIdAndConsumerId(PRODUCT_ID, 5L))
                .thenReturn(false);

        assertThat(service.subscribe(request(5L), ORG_KEY, decision(false, 90, 90))
                        .subscriptionId())
                .isEqualTo(99L);
    }

    @Test
    void unknownProduct_isRefused() {
        when(organisationService.findIdByKey(ORG_KEY)).thenReturn(Optional.of(ORG_ID));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.subscribe(request(4L), ORG_KEY, decision(false, 90, 90)))
                .isInstanceOf(SubscriptionRejectedException.class)
                .extracting(e -> ((SubscriptionRejectedException) e).getReason())
                .isEqualTo(Reason.PRODUCT_NOT_FOUND);
    }

    // ------------------------------------------------------------------ the terms policy set

    @Test
    void validityIsTakenFromThePolicyDecision() {
        organisationExists();
        when(consumerRepository.findById(4L)).thenReturn(Optional.of(consumer(4L, "consumer-a", ORG_ID, false)));

        ProductSubscriptionResponseDTO response = service.subscribe(request(4L), ORG_KEY, decision(false, 365, 30));

        assertThat(response.validityDays()).isEqualTo(30);
        ArgumentCaptor<ProductConsumer> saved = ArgumentCaptor.forClass(ProductConsumer.class);
        verify(productConsumerRepository).save(saved.capture());
        assertThat(saved.getValue().getValidity()).isEqualByComparingTo(BigDecimal.valueOf(30));
    }

    /** A rule cannot grant longer than the ceiling it also stated. */
    @Test
    void validityIsHeldToTheCeilingThePolicyStated() {
        organisationExists();
        when(consumerRepository.findById(4L)).thenReturn(Optional.of(consumer(4L, "consumer-a", ORG_ID, false)));

        assertThat(service.subscribe(request(4L), ORG_KEY, decision(false, 90, 365))
                        .validityDays())
                .isEqualTo(90);
    }

    /** With policy switched off nothing decided the terms, so the grant is short and held. */
    @Test
    void withNoDecision_theGrantGetsTheFallbackValidityAndAwaitsApproval() {
        organisationExists();
        when(consumerRepository.findById(4L)).thenReturn(Optional.of(consumer(4L, "consumer-a", ORG_ID, false)));

        ProductSubscriptionResponseDTO response = service.subscribe(request(4L), ORG_KEY, Optional.empty());

        assertThat(response.validityDays()).isEqualTo(ProductSubscriptionServiceImpl.FALLBACK_VALIDITY_DAYS);
    }

    // ------------------------------------------------------------------ the schedule

    @Test
    void anAbsentScheduleTakesTheDefaults() {
        organisationExists();
        when(consumerRepository.findById(4L)).thenReturn(Optional.of(consumer(4L, "consumer-a", ORG_ID, false)));

        ProductSubscriptionResponseDTO response = service.subscribe(request(4L), ORG_KEY, decision(false, 90, 90));

        assertThat(response.scheduleType()).isEqualTo(ProductSubscriptionRequestDTO.DEFAULT_SCHEDULE_TYPE);
        assertThat(response.scheduleExpression()).isEqualTo(ProductSubscriptionRequestDTO.DEFAULT_SCHEDULE_EXPRESSION);
    }

    @Test
    void aStatedScheduleIsRecordedAsGiven() {
        organisationExists();
        when(consumerRepository.findById(4L)).thenReturn(Optional.of(consumer(4L, "consumer-a", ORG_ID, false)));
        ProductSubscriptionRequestDTO stated = ProductSubscriptionRequestDTO.builder()
                .productId(PRODUCT_ID)
                .consumerId(4L)
                .scheduleType("cron")
                .scheduleExpression("0 * * * *")
                .build();

        ProductSubscriptionResponseDTO response = service.subscribe(stated, ORG_KEY, decision(false, 90, 90));

        assertThat(response.scheduleType()).isEqualTo("cron");
        assertThat(response.scheduleExpression()).isEqualTo("0 * * * *");
    }

    @Test
    void unknownCallingOrganisation_isRefused() {
        when(organisationService.findIdByKey("NOSUCH")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.subscribe(request(null), "NOSUCH", decision(false, 90, 90)))
                .isInstanceOf(SubscriptionRejectedException.class)
                .hasMessageContaining("not known");
    }
}
