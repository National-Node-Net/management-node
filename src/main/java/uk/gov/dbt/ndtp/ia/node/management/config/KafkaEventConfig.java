/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.event.EventPublisher;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.event.KafkaEventPublisher;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.event.NoOpEventPublisher;

/**
 * Chooses how events leave the Node: to Kafka when {@code spring.kafka.enabled} is
 * {@code true}, nowhere otherwise. Exactly one {@link EventPublisher} exists either way, so
 * whatever raises an event never has to ask which.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(KafkaEventProperties.class)
public class KafkaEventConfig {

    @Bean
    @ConditionalOnProperty(prefix = "spring.kafka", name = "enabled", havingValue = "true")
    public EventPublisher kafkaEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            KafkaEventProperties properties,
            Environment environment) {
        log.info(
                "Kafka event publishing is ENABLED - bootstrapServers={} productSubscriptionActivatedTopic={}",
                environment.getProperty("spring.kafka.bootstrap-servers", "localhost:9092"),
                properties.topics().productSubscriptionActivated());
        return new KafkaEventPublisher(kafkaTemplate, objectMapper);
    }

    /**
     * Declares the topic at startup, so that its partitions, replicas and retention are chosen rather
     * than left to the broker's defaults. Only when Kafka is on and {@code topic-settings.create} is
     * true; the broker's own auto-creation is a convenience for local development only.
     */
    @Bean
    @ConditionalOnExpression("${spring.kafka.enabled:false} and ${spring.kafka.topic-settings.create:false}")
    public NewTopic productSubscriptionActivatedTopic(KafkaEventProperties properties) {
        KafkaEventProperties.TopicSettings settings = properties.topicSettings();
        return TopicBuilder.name(properties.topics().productSubscriptionActivated())
                .partitions(settings.partitions())
                .replicas(settings.replicas())
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(settings.retentionDays() * 86_400_000L))
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.kafka", name = "enabled", havingValue = "false", matchIfMissing = true)
    public EventPublisher noOpEventPublisher() {
        log.info("Kafka event publishing is DISABLED - set spring.kafka.enabled=true (KAFKA_ENABLED) to enable");
        return new NoOpEventPublisher();
    }
}
