/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.event;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.ia.node.management.model.event.ProductSubscriptionActivatedEvents;

/** With Kafka switched off, raising an event is harmless and sends nothing anywhere. */
class NoOpEventPublisherTest {

    @Test
    void publishingDoesNothingAndDoesNotThrow() {
        assertThatCode(() -> new NoOpEventPublisher()
                        .publish(
                                "product-subscription.activated",
                                "FEDERATOR_BCC",
                                ProductSubscriptionActivatedEvents.sample()))
                .doesNotThrowAnyException();
    }
}
