/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception;

/**
 * An authorised request refused by an enforcement check that runs after method security - the
 * organisation certificate check or the policy decision. Answered with {@code 403} and the error
 * id the check logged, so a rejection can be traced from the response to the log line.
 *
 * <p>The message is returned to the caller, so it states what was refused, never why in any
 * detail a caller should not see (policy reasons stay in the log).
 */
public class AccessRejectedException extends RuntimeException {

    private final String errorId;

    /**
     * @param message the message returned to the caller
     * @param errorId the id logged with the rejection
     */
    public AccessRejectedException(String message, String errorId) {
        super(message);
        this.errorId = errorId;
    }

    public String getErrorId() {
        return errorId;
    }
}
