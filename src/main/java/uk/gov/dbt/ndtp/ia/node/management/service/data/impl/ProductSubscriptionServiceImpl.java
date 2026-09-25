/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.dbt.ndtp.ia.node.management.exception.SubscriptionRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.SubscriptionRejectedException.Reason;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductSubscriptionRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductSubscriptionResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductSubscriptionPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Consumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Product;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ConsumerRepository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductConsumerRepository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.OrganisationService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductSubscriptionService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;

/**
 * Records the grant, having first decided who it is for and how long it lasts.
 *
 * <p>Four questions are answered in order, and each can end the request: which consumer takes the
 * subscription, does the product exist, does the subscription already exist, and how long is it
 * valid for. Only the last needs the policy decision.
 */
@Slf4j
@Service
public class ProductSubscriptionServiceImpl implements ProductSubscriptionService {

    /**
     * Applied when policy states no validity, which is the case when policy is switched off. Kept
     * deliberately short: a grant nobody authorised should expire soon rather than outlive the
     * bring-up it was made during.
     */
    static final int FALLBACK_VALIDITY_DAYS = 30;

    private final ProductRepository productRepository;
    private final ConsumerRepository consumerRepository;
    private final ProductConsumerRepository productConsumerRepository;
    private final OrganisationService organisationService;

    public ProductSubscriptionServiceImpl(
            ProductRepository productRepository,
            ConsumerRepository consumerRepository,
            ProductConsumerRepository productConsumerRepository,
            OrganisationService organisationService) {
        this.productRepository = productRepository;
        this.consumerRepository = consumerRepository;
        this.productConsumerRepository = productConsumerRepository;
        this.organisationService = organisationService;
    }

    @Override
    @Transactional
    public ProductSubscriptionResponseDTO subscribe(
            ProductSubscriptionRequestDTO request,
            String organisationKey,
            Optional<PolicyDecision<ProductSubscriptionPolicyDecisionDetails>> decision) {

        Long organisationId = organisationService
                .findIdByKey(organisationKey)
                .orElseThrow(() -> new SubscriptionRejectedException(
                        Reason.NO_CONSUMER,
                        "The calling organisation is not known, so it has no consumer to subscribe"));

        Product product = productRepository
                .findById(request.productId())
                .orElseThrow(() -> new SubscriptionRejectedException(
                        Reason.PRODUCT_NOT_FOUND, "No such product: " + request.productId()));

        Consumer consumer = resolveConsumer(request.consumerId(), organisationId, organisationKey);

        // The pair is unique in the database. Checking first turns a constraint violation into a
        // message that names what already exists, which is what the caller needs to act on.
        if (productConsumerRepository.existsByProductIdAndConsumerId(product.getId(), consumer.getId())) {
            throw new SubscriptionRejectedException(
                    Reason.ALREADY_SUBSCRIBED,
                    "Organisation '" + organisationKey + "' is already subscribed to product "
                            + product.getId() + " on consumer '" + consumer.getName() + "' (id "
                            + consumer.getId() + ")");
        }

        ProductSubscriptionPolicyDecisionDetails terms =
                decision.map(PolicyDecision::details).orElse(null);
        int validityDays = validityFor(terms);

        ProductConsumer grant = new ProductConsumer();
        grant.setProduct(product);
        grant.setConsumer(consumer);
        grant.setGrantedTs(Timestamp.from(Instant.now()));
        grant.setValidity(BigDecimal.valueOf(validityDays));
        grant.setScheduleType(request.scheduleTypeOrDefault());
        grant.setScheduleExpression(request.scheduleExpressionOrDefault());
        grant.setDestination(request.destination());
        ProductConsumer saved = productConsumerRepository.save(grant);

        log.info(
                "Subscribed organisation={} consumer={} product={} validityDays={}",
                organisationKey,
                consumer.getId(),
                product.getId(),
                validityDays);

        return ProductSubscriptionResponseDTO.builder()
                .subscriptionId(saved.getId())
                .productId(product.getId())
                .consumerId(consumer.getId())
                .consumerName(consumer.getName())
                .grantedAt(
                        saved.getGrantedTs() == null
                                ? null
                                : saved.getGrantedTs().toLocalDateTime())
                .validityDays(validityDays)
                .maxValidityDays(terms == null ? null : terms.maxValidityDays())
                .scheduleType(saved.getScheduleType())
                .scheduleExpression(saved.getScheduleExpression())
                .permittedScheduleTypes(terms == null ? List.of() : terms.permittedScheduleTypes())
                .build();
    }

    /**
     * Which consumer takes the subscription.
     *
     * <p>A named consumer is used once it is confirmed to belong to the caller's organisation:
     * without that check an id would be a way to subscribe somebody else's consumer. Otherwise the
     * organisation's declared default is used, and failing that its only consumer, because an
     * organisation running exactly one consumer has no ambiguity to resolve. Several consumers and
     * no default is the one case the service cannot decide, and it says so rather than guessing.
     */
    private Consumer resolveConsumer(Long requestedId, Long organisationId, String organisationKey) {
        if (requestedId != null) {
            Consumer named = consumerRepository
                    .findById(requestedId)
                    .orElseThrow(() -> new SubscriptionRejectedException(
                            Reason.CONSUMER_NOT_OWNED, "No such consumer: " + requestedId));
            if (named.getOrg() == null || !organisationId.equals(named.getOrg().getId())) {
                throw new SubscriptionRejectedException(
                        Reason.CONSUMER_NOT_OWNED,
                        "Consumer " + requestedId + " does not belong to organisation '" + organisationKey + "'");
            }
            return named;
        }

        Optional<Consumer> declaredDefault = consumerRepository.findByOrgIdAndIsDefaultTrue(organisationId);
        if (declaredDefault.isPresent()) {
            return declaredDefault.get();
        }

        List<Consumer> consumers = consumerRepository.findByOrgId(organisationId);
        if (consumers.isEmpty()) {
            throw new SubscriptionRejectedException(
                    Reason.NO_CONSUMER,
                    "Organisation '" + organisationKey + "' has no consumer to subscribe. Create one, "
                            + "or name an existing consumer with consumerId");
        }
        if (consumers.size() > 1) {
            throw new SubscriptionRejectedException(
                    Reason.AMBIGUOUS_CONSUMER,
                    "Multiple consumers found, please specify consumer_id. Organisation '" + organisationKey + "' has "
                            + consumers.size() + " consumers and no default consumer");
        }
        return consumers.get(0);
    }

    /**
     * The validity to record, in days. Policy decides it from the caller's and the product's
     * attributes; a rule that states none leaves the service to apply its own short fallback, and
     * a rule that states a validity beyond the ceiling it also stated is held to the ceiling.
     */
    private int validityFor(ProductSubscriptionPolicyDecisionDetails terms) {
        if (terms == null || terms.validityDays() == null || terms.validityDays() < 1) {
            return FALLBACK_VALIDITY_DAYS;
        }
        Integer ceiling = terms.maxValidityDays();
        return ceiling == null || ceiling < 1 ? terms.validityDays() : Math.min(terms.validityDays(), ceiling);
    }
}
