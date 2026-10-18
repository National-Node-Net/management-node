/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A consumer has been subscribed to a product. This is the message sent on the
 * {@code product-subscription.activated} topic (event {@code EVT_001}).
 *
 * <p>It states who the product comes from, who it is for, and which product it is: the minimum a
 * receiver needs to act on the subscription. {@code subscriptionId} is included so that a later
 * answer to this event, or a log line in another service, can be matched to the row that caused it.
 *
 * <p>Organisations are named twice on purpose. {@code organisationKey} is how the Node refers to an
 * organisation; {@code idpClientId} is how the identity provider refers to it. A receiver that
 * talks to the identity provider needs the second, and should not have to look it up.
 *
 * @param eventId unique to this occurrence
 * @param eventType always {@link #EVENT_TYPE}
 * @param version the version of this message's format, {@link #EVENT_VERSION}
 * @param occurredAt when the subscription was recorded
 * @param subscriptionId the id of the subscription row
 * @param productId the subscribed product
 * @param productName the name of the subscribed product
 * @param producingOrganisation the organisation that produces the product
 * @param consumerOrganisation the organisation, and the consumer within it, that is subscribed
 */
public record ProductSubscriptionActivatedEvent(
        UUID eventId,
        String eventType,
        int version,
        Instant occurredAt,
        Long subscriptionId,
        Long productId,
        String productName,
        ProducingOrganisation producingOrganisation,
        ConsumerOrganisation consumerOrganisation)
        implements DomainEvent {

    /** The name of this event. */
    public static final String EVENT_TYPE = "product-subscription.activated";

    /** The version of the format of this message. Raised only by a change that would break a receiver. */
    public static final int EVENT_VERSION = 1;

    /**
     * The organisation the product comes from.
     *
     * @param organisationKey the Node's key for the organisation, for example {@code HEG}
     * @param idpClientId the identity provider's client for the producer, for example {@code FEDERATOR_HEG}
     */
    public record ProducingOrganisation(String organisationKey, String idpClientId) {}

    /**
     * The organisation the product is for, and the consumer within it that took the subscription.
     *
     * @param organisationKey the Node's key for the organisation, for example {@code BCC}
     * @param consumerId the consumer that took the subscription; an organisation may have several
     * @param idpClientId the identity provider's client for the consumer, for example {@code FEDERATOR_BCC}
     */
    public record ConsumerOrganisation(String organisationKey, Long consumerId, String idpClientId) {}
}
