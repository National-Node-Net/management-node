/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.event;

import uk.gov.dbt.ndtp.ia.node.management.model.event.DomainEvent;

/**
 * Sends an event to whoever is listening for it.
 *
 * <p>What the Node does is not allowed to depend on whether anyone heard. An implementation
 * therefore never throws: a failure to send is logged and goes no further, so a subscription that
 * has been recorded is not undone because a broker was away.
 */
public interface EventPublisher {

    /**
     * Sends {@code event} to {@code topic}.
     *
     * @param topic where to send it
     * @param key keeps events with the same key in order relative to each other
     * @param event what happened
     */
    void publish(String topic, String key, DomainEvent event);
}
