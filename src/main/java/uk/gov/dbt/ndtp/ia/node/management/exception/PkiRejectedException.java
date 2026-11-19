/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception;

/**
 * The PKI backend refused a request it was able to process, for example a serial it does not know or a
 * permission the Management Node lacks. Never retried: sending the same request again gets the same answer.
 * Answered with {@code 502}.
 */
public class PkiRejectedException extends PkiException {

    public PkiRejectedException(String message) {
        super(message);
    }

    public PkiRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
