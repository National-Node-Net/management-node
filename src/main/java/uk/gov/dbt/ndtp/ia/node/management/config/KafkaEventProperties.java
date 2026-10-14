/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the events the Node publishes to Kafka. The connection itself (bootstrap servers,
 * serialisers, the consumer group) is Spring's own {@code spring.kafka.*}; this holds only what is
 * specific to the Node.
 *
 * @param enabled master switch. Defaults to {@code false}: with it off nothing is sent, no broker
 *     is needed, and the Node behaves exactly as it did before Kafka was introduced. Set it to
 *     {@code true} in any environment where other services act on the Node's events.
 * @param topics the topic each event is sent on
 * @param topicSettings how the topics are declared when the Node is asked to create them
 */
@ConfigurationProperties(prefix = "spring.kafka")
public record KafkaEventProperties(
        boolean enabled, @DefaultValue Topics topics, @DefaultValue TopicSettings topicSettings) {

    /**
     * @param productSubscriptionActivated the topic for {@code product-subscription.activated}
     */
    public record Topics(@DefaultValue("product-subscription.activated") String productSubscriptionActivated) {}

    /**
     * The shape of a topic the Node declares at startup. Only used when {@code create} is true.
     *
     * @param create whether to declare the topics at startup. Off by default: in a shared environment
     *     topics are created deliberately, with the partitions, replicas and retention that suit them
     * @param partitions number of partitions
     * @param replicas replication factor; cannot exceed the number of brokers
     * @param retentionDays how long messages are kept
     */
    public record TopicSettings(
            @DefaultValue("false") boolean create,
            @DefaultValue("1") int partitions,
            @DefaultValue("1") int replicas,
            @DefaultValue("7") int retentionDays) {}
}
