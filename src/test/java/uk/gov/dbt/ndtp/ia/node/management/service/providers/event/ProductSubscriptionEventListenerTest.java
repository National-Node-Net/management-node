/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.event;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.dbt.ndtp.ia.node.management.config.KafkaEventProperties;
import uk.gov.dbt.ndtp.ia.node.management.model.event.ProductSubscriptionActivatedEvent;
import uk.gov.dbt.ndtp.ia.node.management.model.event.ProductSubscriptionActivatedEvents;

/** The listener hands the event to the publisher on the configured topic, keyed by the consumer's client. */
@ExtendWith(MockitoExtension.class)
class ProductSubscriptionEventListenerTest {

    @Mock
    private EventPublisher eventPublisher;

    @Test
    void sendsOnTheConfiguredTopicKeyedByTheConsumersClient() {
        KafkaEventProperties properties = new KafkaEventProperties(
                true,
                new KafkaEventProperties.Topics("custom.topic"),
                new KafkaEventProperties.TopicSettings(false, 1, 1, 7));
        ProductSubscriptionActivatedEvent event = ProductSubscriptionActivatedEvents.sample();

        new ProductSubscriptionEventListener(eventPublisher, properties).onProductSubscriptionActivated(event);

        verify(eventPublisher).publish("custom.topic", "FEDERATOR_BCC", event);
    }
}
