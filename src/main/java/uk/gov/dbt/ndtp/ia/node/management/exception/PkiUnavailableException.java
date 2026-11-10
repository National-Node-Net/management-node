/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception;

/**
 * The PKI backend could not be reached, or answered with an error that may pass: a server error, a sealed or
 * standby node, too many requests. Retried a bounded number of times. Answered with {@code 503}.
 */
public class PkiUnavailableException extends PkiException {

    public PkiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
