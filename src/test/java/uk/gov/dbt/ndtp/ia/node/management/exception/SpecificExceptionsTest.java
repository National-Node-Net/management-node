/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for the specific exception types that extend AuthenticationProcessingException.
 */
class SpecificExceptionsTest {

    private static final String CLIENT_ID = "test-client";
    private static final String MESSAGE = "Test exception message";
    private static final Exception CAUSE = new RuntimeException("Test cause");

    @Test
    void resourceAccessParsingException_withMessageAndClientId_shouldIncludeClientIdInMessage() {
        // Act
        ResourceAccessParsingException exception = new ResourceAccessParsingException(MESSAGE, CLIENT_ID);

        // Assert
        assertTrue(exception.getMessage().contains(MESSAGE));
        assertTrue(exception.getMessage().contains(CLIENT_ID));
        assertEquals(CLIENT_ID, exception.getClientId());
        assertTrue(exception instanceof AuthenticationProcessingException);
    }

    @Test
    void resourceAccessParsingException_withMessageCauseAndClientId_shouldIncludeClientIdInMessage() {
        // Act
        ResourceAccessParsingException exception = new ResourceAccessParsingException(MESSAGE, CAUSE, CLIENT_ID);

        // Assert
        assertTrue(exception.getMessage().contains(MESSAGE));
        assertTrue(exception.getMessage().contains(CLIENT_ID));
        assertEquals(CLIENT_ID, exception.getClientId());
        assertEquals(CAUSE, exception.getCause());
        assertTrue(exception instanceof AuthenticationProcessingException);
    }

    @Test
    void jwtClaimParsingException_withMessageAndClientId_shouldIncludeClientIdInMessage() {
        // Act
        JwtClaimParsingException exception = new JwtClaimParsingException(MESSAGE, CLIENT_ID);

        // Assert
        assertTrue(exception.getMessage().contains(MESSAGE));
        assertTrue(exception.getMessage().contains(CLIENT_ID));
        assertEquals(CLIENT_ID, exception.getClientId());
        assertTrue(exception instanceof AuthenticationProcessingException);
    }

    @Test
    void jwtClaimParsingException_withMessageCauseAndClientId_shouldIncludeClientIdInMessage() {
        // Act
        JwtClaimParsingException exception = new JwtClaimParsingException(MESSAGE, CAUSE, CLIENT_ID);

        // Assert
        assertTrue(exception.getMessage().contains(MESSAGE));
        assertTrue(exception.getMessage().contains(CLIENT_ID));
        assertEquals(CLIENT_ID, exception.getClientId());
        assertEquals(CAUSE, exception.getCause());
        assertTrue(exception instanceof AuthenticationProcessingException);
    }

    @Test
    void tokenIntrospectionException_withMessageAndClientId_shouldIncludeClientIdInMessage() {
        // Act
        TokenIntrospectionException exception = new TokenIntrospectionException(MESSAGE, CLIENT_ID);

        // Assert
        assertTrue(exception.getMessage().contains(MESSAGE));
        assertTrue(exception.getMessage().contains(CLIENT_ID));
        assertEquals(CLIENT_ID, exception.getClientId());
        assertTrue(exception instanceof AuthenticationProcessingException);
    }

    @Test
    void tokenIntrospectionException_withMessageCauseAndClientId_shouldIncludeClientIdInMessage() {
        // Act
        TokenIntrospectionException exception = new TokenIntrospectionException(MESSAGE, CAUSE, CLIENT_ID);

        // Assert
        assertTrue(exception.getMessage().contains(MESSAGE));
        assertTrue(exception.getMessage().contains(CLIENT_ID));
        assertEquals(CLIENT_ID, exception.getClientId());
        assertEquals(CAUSE, exception.getCause());
        assertTrue(exception instanceof AuthenticationProcessingException);
    }
}
