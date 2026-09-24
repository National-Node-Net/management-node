/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.exception;

/** Discovery criteria that are malformed; answered as {@code 400 Bad Request} with this message. */
public class InvalidSearchCriteriaException extends RuntimeException {

    public InvalidSearchCriteriaException(String message) {
        super(message);
    }
}
