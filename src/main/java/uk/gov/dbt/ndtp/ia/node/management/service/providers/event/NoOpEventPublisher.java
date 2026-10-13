/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.event;

import lombok.extern.slf4j.Slf4j;
import uk.gov.dbt.ndtp.ia.node.management.model.event.DomainEvent;

/**
 * Stands in for the Kafka publisher when {@code application.kafka.enabled} is {@code false}, so
 * that code which raises events does not need to know whether Kafka is switched on.
 */
@Slf4j
public class NoOpEventPublisher implements EventPublisher {

    @Override
    public void publish(String topic, String key, DomainEvent event) {
        log.debug(
                "Kafka is disabled, event not published type={} eventId={} topic={}",
                event.eventType(),
                event.eventId(),
                topic);
    }
}
