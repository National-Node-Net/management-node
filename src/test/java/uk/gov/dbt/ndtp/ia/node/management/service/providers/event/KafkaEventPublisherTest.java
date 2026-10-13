/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import uk.gov.dbt.ndtp.ia.node.management.model.event.ProductSubscriptionActivatedEvent;
import uk.gov.dbt.ndtp.ia.node.management.model.event.ProductSubscriptionActivatedEvents;

@ExtendWith(MockitoExtension.class)
class KafkaEventPublisherTest {

    private static final String TOPIC = "product-subscription.activated";
    private static final String KEY = "FEDERATOR_BCC";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private KafkaEventPublisher publisher;

    private final ProductSubscriptionActivatedEvent event = ProductSubscriptionActivatedEvents.sample();

    @BeforeEach
    void setUp() {
        publisher = new KafkaEventPublisher(kafkaTemplate, objectMapper);
    }

    private static CompletableFuture<SendResult<String, String>> acknowledged() {
        RecordMetadata metadata = new RecordMetadata(new TopicPartition(TOPIC, 0), 0L, 0, 0L, 0, 0);
        return CompletableFuture.completedFuture(new SendResult<>(new ProducerRecord<>(TOPIC, KEY, "{}"), metadata));
    }

    @Test
    void sendsTheEventAsJsonToTheTopicWithTheGivenKey() throws Exception {
        when(kafkaTemplate.send(eq(TOPIC), eq(KEY), anyString())).thenReturn(acknowledged());

        publisher.publish(TOPIC, KEY, event);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TOPIC), eq(KEY), payload.capture());
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.get("eventId").asText()).isEqualTo(ProductSubscriptionActivatedEvents.EVENT_ID.toString());
        assertThat(json.get("eventType").asText()).isEqualTo("product-subscription.activated");
        assertThat(json.get("version").asInt()).isEqualTo(1);
        assertThat(json.get("occurredAt").asText()).isEqualTo("2026-10-07T13:30:00Z");
        assertThat(json.get("subscriptionId").asLong()).isEqualTo(15L);
        assertThat(json.get("productId").asLong()).isEqualTo(1L);
        assertThat(json.get("productName").asText()).isEqualTo("BrownfieldLandAvailability");
        assertThat(json.at("/producingOrganisation/organisationKey").asText()).isEqualTo("HEG");
        assertThat(json.at("/producingOrganisation/idpClientId").asText()).isEqualTo("FEDERATOR_HEG");
        assertThat(json.at("/consumerOrganisation/organisationKey").asText()).isEqualTo("BCC");
        assertThat(json.at("/consumerOrganisation/consumerId").asLong()).isEqualTo(2L);
        assertThat(json.at("/consumerOrganisation/idpClientId").asText()).isEqualTo("FEDERATOR_BCC");
    }

    @Test
    void aBrokerThatRejectsTheRecord_doesNotThrow() {
        when(kafkaTemplate.send(eq(TOPIC), eq(KEY), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker away")));

        assertThatCode(() -> publisher.publish(TOPIC, KEY, event)).doesNotThrowAnyException();
    }

    @Test
    void aSendThatFailsImmediately_doesNotThrow() {
        when(kafkaTemplate.send(eq(TOPIC), eq(KEY), anyString())).thenThrow(new KafkaException("no metadata"));

        assertThatCode(() -> publisher.publish(TOPIC, KEY, event)).doesNotThrowAnyException();
    }

    @Test
    void anEventThatCannotBeSerialised_isNotSentAndDoesNotThrow() throws Exception {
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsString(any())).thenThrow(new JsonProcessingException("cannot write") {});
        KafkaEventPublisher withFailingMapper = new KafkaEventPublisher(kafkaTemplate, failing);

        assertThatCode(() -> withFailingMapper.publish(TOPIC, KEY, event)).doesNotThrowAnyException();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }
}
