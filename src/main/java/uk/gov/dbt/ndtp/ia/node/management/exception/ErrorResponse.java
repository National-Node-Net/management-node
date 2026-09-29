/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents an error response to be sent to clients.
 * Contains a status code, an error message, and a unique error ID without exposing stack traces.
 * A refusal by policy also carries the policy's reasons; every other error omits the field.
 */
@Data
@NoArgsConstructor
public class ErrorResponse {

    /**
     * The HTTP status code
     */
    private int status;

    /**
     * A user-friendly error message
     */
    private String message;

    /**
     * Stable reason codes explaining a refusal by policy, e.g. {@code organisation.clearance_insufficient};
     * omitted from the JSON when there are none
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> reasons = List.of();

    /**
     * A unique identifier for the error
     */
    private String errorId;

    public ErrorResponse(int status, String message, String errorId) {
        this(status, message, List.of(), errorId);
    }

    public ErrorResponse(int status, String message, List<String> reasons, String errorId) {
        this.status = status;
        this.message = message;
        this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
        this.errorId = errorId;
    }
}
