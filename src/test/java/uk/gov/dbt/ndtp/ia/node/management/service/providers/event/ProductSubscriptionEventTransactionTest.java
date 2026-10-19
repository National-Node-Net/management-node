/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.dbt.ndtp.ia.node.management.config.KafkaEventProperties;
import uk.gov.dbt.ndtp.ia.node.management.model.event.ProductSubscriptionActivatedEvent;
import uk.gov.dbt.ndtp.ia.node.management.model.event.ProductSubscriptionActivatedEvents;

/**
 * The rule the whole design rests on: the event is sent after the subscription's transaction
 * commits, and not at all if it is rolled back. A subscription that was undone must never have been
 * announced, or the identity provider would grant access to something that does not exist.
 *
 * <p>The event is raised inside a transaction, as {@code ProductSubscriptionServiceImpl} does, and
 * the transaction is then committed or rolled back. The transaction manager does nothing: only the
 * outcome matters, and Spring's transaction synchronisation decides what the listener sees.
 */
class ProductSubscriptionEventTransactionTest {

    private static final KafkaEventProperties PROPERTIES = new KafkaEventProperties(
            true,
            new KafkaEventProperties.Topics("test.topic"),
            new KafkaEventProperties.TopicSettings(false, 1, 1, 7));

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TransactionSupport.class, ProductSubscriptionEventListener.class)
            .withBean(EventPublisher.class, () -> mock(EventPublisher.class))
            .withBean(KafkaEventProperties.class, () -> PROPERTIES);

    private final ProductSubscriptionActivatedEvent event = ProductSubscriptionActivatedEvents.sample();

    @Test
    void aCommittedTransaction_sendsTheEventOnce() {
        runner.run(context -> {
            TransactionTemplate transaction =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

            transaction.executeWithoutResult(status -> context.publishEvent(event));

            verify(context.getBean(EventPublisher.class)).publish("test.topic", "FEDERATOR_BCC", event);
        });
    }

    @Test
    void aRolledBackTransaction_sendsNothing() {
        runner.run(context -> {
            TransactionTemplate transaction =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

            transaction.executeWithoutResult(status -> {
                context.publishEvent(event);
                status.setRollbackOnly();
            });

            verify(context.getBean(EventPublisher.class), never()).publish(any(), any(), any());
        });
    }

    @Configuration
    @EnableTransactionManagement
    static class TransactionSupport {

        @Bean
        PlatformTransactionManager transactionManager() {
            return new NoOpTransactionManager();
        }
    }

    /** Begins, commits and rolls back without touching anything. */
    static class NoOpTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // no-op: a fake transaction manager with no real resource to manage
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // no-op: a fake transaction manager with no real resource to manage
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // no-op: a fake transaction manager with no real resource to manage
        }
    }
}
