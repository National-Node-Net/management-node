/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception;

import lombok.Getter;

/**
 * Base exception class for authentication processing errors.
 * This exception is thrown when there's an error during the authentication process.
 */
@Getter
public class AuthenticationProcessingException extends RuntimeException {

    /**
     * -- GETTER --
     * Gets the client ID associated with this exception.
     *
     * @return the client ID
     */
    private final String clientId;

    /**
     * Constructs a new AuthenticationProcessingException with the specified detail message.
     *
     * @param message  the detail message
     * @param clientId the client ID associated with the authentication
     */
    public AuthenticationProcessingException(String message, String clientId) {
        super(message + " [Client ID: " + clientId + "]");
        this.clientId = clientId;
    }

    /**
     * Constructs a new AuthenticationProcessingException with the specified detail message and cause.
     *
     * @param message  the detail message
     * @param cause    the cause of the exception
     * @param clientId the client ID associated with the authentication
     */
    public AuthenticationProcessingException(String message, Throwable cause, String clientId) {
        super(message + " [Client ID: " + clientId + "]", cause);
        this.clientId = clientId;
    }
}
