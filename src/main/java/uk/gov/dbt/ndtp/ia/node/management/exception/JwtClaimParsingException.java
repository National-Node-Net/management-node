/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception;

/**
 * Exception thrown when there's an error parsing JWT claims.
 * This can happen when the JWT structure is unexpected or when claims
 * don't contain the expected data.
 */
public class JwtClaimParsingException extends AuthenticationProcessingException {

    /**
     * Constructs a new JwtClaimParsingException with the specified detail message.
     *
     * @param message  the detail message
     * @param clientId the client ID associated with the authentication
     */
    public JwtClaimParsingException(String message, String clientId) {
        super(message, clientId);
    }

    /**
     * Constructs a new JwtClaimParsingException with the specified detail message and cause.
     *
     * @param message  the detail message
     * @param cause    the cause of the exception
     * @param clientId the client ID associated with the authentication
     */
    public JwtClaimParsingException(String message, Throwable cause, String clientId) {
        super(message, cause, clientId);
    }
}
