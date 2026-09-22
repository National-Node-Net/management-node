/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception;

import lombok.Getter;

/**
 * A subscription request that cannot be carried out for a reason the caller can act on: the
 * consumer could not be chosen, or the subscription already exists.
 *
 * <p>Separate from a policy refusal. Policy decides whether the caller may subscribe at all and on
 * what terms; this says the request was permitted but does not describe a subscription that can be
 * made. The distinction is what lets "you may not do this" answer 403 while "this is already done"
 * answers 409.
 */
@Getter
public class SubscriptionRejectedException extends RuntimeException {

    /** What went wrong, so the handler can map it to a status without matching on the message. */
    public enum Reason {
        /** The organisation has no consumer that could take the subscription. */
        NO_CONSUMER,
        /** Several consumers could, and the request did not say which. */
        AMBIGUOUS_CONSUMER,
        /** The named consumer exists but belongs to another organisation. */
        CONSUMER_NOT_OWNED,
        /** The product does not exist. */
        PRODUCT_NOT_FOUND,
        /** This consumer already holds a grant on this product. */
        ALREADY_SUBSCRIBED
    }

    private final transient Reason reason;

    public SubscriptionRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
}
