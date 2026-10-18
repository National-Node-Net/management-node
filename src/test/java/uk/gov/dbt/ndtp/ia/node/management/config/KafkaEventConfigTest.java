/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.event.EventPublisher;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.event.KafkaEventPublisher;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.event.NoOpEventPublisher;

class KafkaEventConfigTest {

    @SuppressWarnings("unchecked")
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaEventConfig.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class));

    @Test
    void withNothingConfigured_kafkaIsOffAndTheTopicHasItsDefaultName() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(EventPublisher.class);
            assertThat(context).getBean(EventPublisher.class).isInstanceOf(NoOpEventPublisher.class);
            KafkaEventProperties properties = context.getBean(KafkaEventProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.topics().productSubscriptionActivated()).isEqualTo("product-subscription.activated");
        });
    }

    @Test
    void whenDisabled_noOpPublisherIsUsed() {
        runner.withPropertyValues("spring.kafka.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(EventPublisher.class);
            assertThat(context).getBean(EventPublisher.class).isInstanceOf(NoOpEventPublisher.class);
        });
    }

    @Test
    void whenEnabled_kafkaPublisherIsUsed() {
        runner.withPropertyValues("spring.kafka.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(EventPublisher.class);
            assertThat(context).getBean(EventPublisher.class).isInstanceOf(KafkaEventPublisher.class);
        });
    }

    @Test
    void topicsAreNotDeclaredByDefault() {
        runner.withPropertyValues("spring.kafka.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(NewTopic.class));
    }

    @Test
    void whenKafkaIsOffTopicsAreNotDeclaredEvenIfCreateIsOn() {
        runner.withPropertyValues("spring.kafka.topic-settings.create=true")
                .run(context -> assertThat(context).doesNotHaveBean(NewTopic.class));
    }

    @Test
    void whenKafkaIsOnAndCreateIsOn_theTopicIsDeclaredWithTheConfiguredShape() {
        runner.withPropertyValues(
                        "spring.kafka.enabled=true",
                        "spring.kafka.topic-settings.create=true",
                        "spring.kafka.topic-settings.partitions=3",
                        "spring.kafka.topic-settings.replicas=2",
                        "spring.kafka.topic-settings.retention-days=14")
                .run(context -> {
                    NewTopic topic = context.getBean(NewTopic.class);
                    assertThat(topic.name()).isEqualTo("product-subscription.activated");
                    assertThat(topic.numPartitions()).isEqualTo(3);
                    assertThat(topic.replicationFactor()).isEqualTo((short) 2);
                    assertThat(topic.configs())
                            .containsEntry("retention.ms", String.valueOf(14L * 24 * 60 * 60 * 1000));
                });
    }

    @Test
    void theTopicNameCanBeConfigured() {
        runner.withPropertyValues("spring.kafka.topics.product-subscription-activated=other.topic")
                .run(context -> assertThat(context.getBean(KafkaEventProperties.class)
                                .topics()
                                .productSubscriptionActivated())
                        .isEqualTo("other.topic"));
    }
}
