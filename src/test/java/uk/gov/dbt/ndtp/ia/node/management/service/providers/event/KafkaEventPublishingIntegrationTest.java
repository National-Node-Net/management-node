/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import uk.gov.dbt.ndtp.ia.node.management.config.KafkaEventConfig;
import uk.gov.dbt.ndtp.ia.node.management.model.event.ProductSubscriptionActivatedEvent;
import uk.gov.dbt.ndtp.ia.node.management.model.event.ProductSubscriptionActivatedEvents;

/**
 * The event, sent the way the application sends it (Spring Boot's own Kafka and Jackson
 * configuration and the publisher), against a real broker started for the test, and
 * read back from the topic as another service would read it.
 */
@SpringBootTest(classes = KafkaEventPublishingIntegrationTest.KafkaOnly.class, properties = "spring.kafka.enabled=true")
@EmbeddedKafka(
        partitions = 1,
        topics = KafkaEventPublishingIntegrationTest.TOPIC,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class KafkaEventPublishingIntegrationTest {

    static final String TOPIC = "product-subscription.activated";

    @Configuration
    @ImportAutoConfiguration({KafkaAutoConfiguration.class, JacksonAutoConfiguration.class})
    @Import(KafkaEventConfig.class)
    static class KafkaOnly {}

    @Autowired
    private EventPublisher publisher;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void theEventArrivesOnTheTopicAsJson_keyedByTheConsumersClient() throws Exception {
        Map<String, Object> properties = KafkaTestUtils.consumerProps("integration-test", "true", broker);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (var consumer = new DefaultKafkaConsumerFactory<>(
                        properties, new StringDeserializer(), new StringDeserializer())
                .createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, TOPIC);

            ProductSubscriptionActivatedEvent event = ProductSubscriptionActivatedEvents.sample();
            String key = event.consumerOrganisation().idpClientId();

            publisher.publish(TOPIC, key, event);

            ConsumerRecord<String, String> record =
                    KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(15));
            assertThat(record.key()).isEqualTo(key);
            JsonNode json = objectMapper.readTree(record.value());
            assertThat(json.get("eventId").asText()).isEqualTo(event.eventId().toString());
            assertThat(json.get("eventType").asText()).isEqualTo(event.eventType());
            assertThat(json.get("version").asInt()).isEqualTo(event.version());
            assertThat(json.get("occurredAt").asText())
                    .isEqualTo(event.occurredAt().toString());
            assertThat(json.get("subscriptionId").asLong()).isEqualTo(event.subscriptionId());
            assertThat(json.get("productId").asLong()).isEqualTo(event.productId());
            assertThat(json.get("productName").asText()).isEqualTo(event.productName());
            assertThat(json.at("/producingOrganisation/organisationKey").asText())
                    .isEqualTo(event.producingOrganisation().organisationKey());
            assertThat(json.at("/producingOrganisation/idpClientId").asText())
                    .isEqualTo(event.producingOrganisation().idpClientId());
            assertThat(json.at("/consumerOrganisation/organisationKey").asText())
                    .isEqualTo(event.consumerOrganisation().organisationKey());
            assertThat(json.at("/consumerOrganisation/consumerId").asLong())
                    .isEqualTo(event.consumerOrganisation().consumerId());
            assertThat(json.at("/consumerOrganisation/idpClientId").asText()).isEqualTo(key);
        }
    }
}
