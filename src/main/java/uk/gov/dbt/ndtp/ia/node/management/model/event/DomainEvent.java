/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Something that has happened in the Node and that other services may want to react to.
 *
 * <p>Every event carries the same four members so that whatever sends it, and whatever reads it,
 * can identify it without knowing its type: {@code eventId} is unique per occurrence and lets a
 * receiver recognise a message it has already handled, {@code eventType} is the event's name
 * (which is also the name of the topic it is sent on, unless configuration says otherwise), and
 * {@code occurredAt} is when it happened, not when it was sent.
 */
public interface DomainEvent {

    UUID eventId();

    String eventType();

    Instant occurredAt();

    /**
     * The version of this event's format, starting at 1. It is raised only when a change would break
     * a receiver; adding a field does not.
     */
    int version();
}
