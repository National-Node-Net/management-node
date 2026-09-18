/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception;

import java.util.List;

/**
 * An authorised request refused by an enforcement check that runs after method security - the
 * organisation certificate check or the policy decision. Answered with {@code 403} and the error
 * id the check logged, so a rejection can be traced from the response to the log line.
 *
 * <p>The message and reasons are returned to the caller. The message states what was refused; the
 * reasons are the stable codes a caller may act on (a policy refusal passes those from
 * {@code PolicyDecision.callerReasons()}), never details of how the service is configured.
 */
public class AccessRejectedException extends RuntimeException {

    private final String errorId;

    private final List<String> reasons;

    /**
     * @param message the message returned to the caller
     * @param errorId the id logged with the rejection
     */
    public AccessRejectedException(String message, String errorId) {
        this(message, List.of(), errorId);
    }

    /**
     * @param message the message returned to the caller
     * @param reasons reason codes returned to the caller; null for none
     * @param errorId the id logged with the rejection
     */
    public AccessRejectedException(String message, List<String> reasons, String errorId) {
        super(message);
        this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
        this.errorId = errorId;
    }

    public String getErrorId() {
        return errorId;
    }

    /** Reason codes returned to the caller; never null, empty when there are none. */
    public List<String> getReasons() {
        return reasons;
    }
}
