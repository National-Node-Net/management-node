/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AuthenticationProcessingExceptionTest {

    private static final String CLIENT_ID = "test-client";
    private static final String MESSAGE = "Test exception message";
    private static final Exception CAUSE = new RuntimeException("Test cause");

    @Test
    void constructor_withMessageAndClientId_shouldIncludeClientIdInMessage() {
        // Act
        AuthenticationProcessingException exception = new AuthenticationProcessingException(MESSAGE, CLIENT_ID);

        // Assert
        assertTrue(exception.getMessage().contains(MESSAGE));
        assertTrue(exception.getMessage().contains(CLIENT_ID));
        assertEquals(CLIENT_ID, exception.getClientId());
    }

    @Test
    void constructor_withMessageCauseAndClientId_shouldIncludeClientIdInMessage() {
        // Act
        AuthenticationProcessingException exception = new AuthenticationProcessingException(MESSAGE, CAUSE, CLIENT_ID);

        // Assert
        assertTrue(exception.getMessage().contains(MESSAGE));
        assertTrue(exception.getMessage().contains(CLIENT_ID));
        assertEquals(CLIENT_ID, exception.getClientId());
        assertEquals(CAUSE, exception.getCause());
    }
}
