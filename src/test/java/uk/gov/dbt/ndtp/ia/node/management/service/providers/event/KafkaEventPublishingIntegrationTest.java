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
import uk.gov.dbt.ndtp.ia.node.management.model.event.ProductSubscriptionActivatedEvents;

/**
 * The event, sent the way the application sends it (Spring Boot's own Kafka and Jackson
 * configuration, the listener and the publisher), against a real broker started for the test, and
 * read back from the topic as another service would read it.
 */
@SpringBootTest(
        classes = KafkaEventPublishingIntegrationTest.KafkaOnly.class,
        properties = "application.kafka.enabled=true")
@EmbeddedKafka(
        partitions = 1,
        topics = KafkaEventPublishingIntegrationTest.TOPIC,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class KafkaEventPublishingIntegrationTest {

    static final String TOPIC = "product-subscription.activated";

    @Configuration
    @ImportAutoConfiguration({KafkaAutoConfiguration.class, JacksonAutoConfiguration.class})
    @Import({KafkaEventConfig.class, ProductSubscriptionEventListener.class})
    static class KafkaOnly {}

    @Autowired
    private ProductSubscriptionEventListener listener;

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

            listener.onProductSubscriptionActivated(ProductSubscriptionActivatedEvents.sample());

            ConsumerRecord<String, String> record =
                    KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(15));
            assertThat(record.key()).isEqualTo("FEDERATOR_BCC");
            JsonNode json = objectMapper.readTree(record.value());
            assertThat(json.get("eventType").asText()).isEqualTo("product-subscription.activated");
            assertThat(json.get("version").asInt()).isEqualTo(1);
            assertThat(json.get("subscriptionId").asLong()).isEqualTo(15L);
            assertThat(json.get("productName").asText()).isEqualTo("BrownfieldLandAvailability");
            assertThat(json.at("/producingOrganisation/idpClientId").asText()).isEqualTo("FEDERATOR_HEG");
            assertThat(json.at("/consumerOrganisation/idpClientId").asText()).isEqualTo("FEDERATOR_BCC");
            assertThat(json.get("occurredAt").asText()).isEqualTo("2026-10-07T13:30:00Z");
        }
    }
}
