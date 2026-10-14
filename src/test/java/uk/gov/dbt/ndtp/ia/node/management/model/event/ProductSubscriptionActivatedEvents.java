/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.event;

import java.time.Instant;
import java.util.UUID;
import uk.gov.dbt.ndtp.ia.node.management.model.event.ProductSubscriptionActivatedEvent.ConsumerOrganisation;
import uk.gov.dbt.ndtp.ia.node.management.model.event.ProductSubscriptionActivatedEvent.ProducingOrganisation;

/** A subscription event for tests: BCC subscribing to a product of HEG. */
public final class ProductSubscriptionActivatedEvents {

    public static final UUID EVENT_ID = UUID.fromString("7f3c2a1e-0b4d-4d7a-9a52-1c2e8f6d3b10");
    public static final Instant OCCURRED_AT = Instant.parse("2026-10-07T13:30:00Z");

    private ProductSubscriptionActivatedEvents() {}

    public static ProductSubscriptionActivatedEvent sample() {
        return new ProductSubscriptionActivatedEvent(
                EVENT_ID,
                ProductSubscriptionActivatedEvent.EVENT_TYPE,
                ProductSubscriptionActivatedEvent.EVENT_VERSION,
                OCCURRED_AT,
                15L,
                1L,
                "BrownfieldLandAvailability",
                new ProducingOrganisation("HEG", "FEDERATOR_HEG"),
                new ConsumerOrganisation("BCC", 2L, "FEDERATOR_BCC"));
    }
}
