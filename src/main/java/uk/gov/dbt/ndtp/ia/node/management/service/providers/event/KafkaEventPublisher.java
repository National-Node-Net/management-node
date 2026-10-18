/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.kafka.core.KafkaTemplate;
import uk.gov.dbt.ndtp.ia.node.management.model.event.DomainEvent;

/**
 * Sends events to Kafka as JSON, using Spring's {@link KafkaTemplate}.
 *
 * <p>Sending is asynchronous: {@link #publish} returns once the record is handed to the producer,
 * and the outcome is logged when the broker answers. The event is written as a JSON string rather
 * than by a JSON serialiser on the producer, so that what is on the topic is exactly what the
 * application's own {@link ObjectMapper} produces (ISO-8601 timestamps, no Java type headers that
 * a receiver written in another stack would not know what to do with).
 */
@Slf4j
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(String topic, String key, DomainEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, key, payload).whenComplete((result, failure) -> {
                if (failure != null) {
                    log.error(
                            "Event not published type={} eventId={} topic={} key={}",
                            event.eventType(),
                            event.eventId(),
                            topic,
                            key,
                            failure);
                    return;
                }
                RecordMetadata metadata = result.getRecordMetadata();
                log.info(
                        "Event published type={} eventId={} topic={} key={} partition={} offset={}",
                        event.eventType(),
                        event.eventId(),
                        topic,
                        key,
                        metadata.partition(),
                        metadata.offset());
            });
        } catch (Exception e) {
            // Never throw: the subscription is already committed and must not depend on Kafka.
            log.error(
                    "Event not published type={} eventId={} topic={} key={}",
                    event.eventType(),
                    event.eventId(),
                    topic,
                    key,
                    e);
        }
    }
}
