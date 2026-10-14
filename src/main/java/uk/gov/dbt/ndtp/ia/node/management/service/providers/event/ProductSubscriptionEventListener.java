/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uk.gov.dbt.ndtp.ia.node.management.config.KafkaEventProperties;
import uk.gov.dbt.ndtp.ia.node.management.model.event.ProductSubscriptionActivatedEvent;

/**
 * Sends {@code product-subscription.activated} once the subscription that caused it is committed.
 *
 * <p>The subscription is recorded and the event raised in one transaction, but the event is only
 * sent after that transaction commits. Sent earlier, a subscription that was then rolled back
 * would already have been announced, and the identity provider would grant access to a
 * subscription that does not exist.
 *
 * <p>The message is keyed by the consumer's identity provider client, so that everything said
 * about one client reaches a receiver in the order it happened.
 */
@Component
public class ProductSubscriptionEventListener {

    private final EventPublisher eventPublisher;
    private final KafkaEventProperties properties;

    public ProductSubscriptionEventListener(EventPublisher eventPublisher, KafkaEventProperties properties) {
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProductSubscriptionActivated(ProductSubscriptionActivatedEvent event) {
        eventPublisher.publish(
                properties.topics().productSubscriptionActivated(),
                event.consumerOrganisation().idpClientId(),
                event);
    }
}
