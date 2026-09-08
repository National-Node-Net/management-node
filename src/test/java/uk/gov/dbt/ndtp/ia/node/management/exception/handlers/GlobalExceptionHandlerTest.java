/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception.handlers;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import uk.gov.dbt.ndtp.ia.node.management.exception.AuthenticationProcessingException;
import uk.gov.dbt.ndtp.ia.node.management.exception.ErrorResponse;
import uk.gov.dbt.ndtp.ia.node.management.exception.JwtClaimParsingException;

/**
 * Tests for the GlobalExceptionHandler class.
 * Verifies that each exception handler method returns the correct HTTP status code
 * and ErrorResponse object with appropriate values.
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;

    @Mock
    private WebRequest webRequest;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        exceptionHandler = new GlobalExceptionHandler();
    }

    @Test
    void handleAuthenticationProcessingException_shouldReturnUnauthorizedStatus() {
        // Arrange
        String clientId = "test-client";
        String message = "Authentication failed";
        AuthenticationProcessingException exception = new AuthenticationProcessingException(message, clientId);

        // Act
        ResponseEntity<ErrorResponse> response =
                exceptionHandler.handleAuthenticationProcessingException(exception, webRequest);

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        ErrorResponse errorResponse = response.getBody();
        assertNotNull(errorResponse);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), errorResponse.getStatus());
        assertTrue(errorResponse.getMessage().contains(message));
        assertNotNull(errorResponse.getErrorId());
    }

    @Test
    void handleAuthenticationProcessingException_withSubclass_shouldReturnUnauthorizedStatus() {
        // Arrange
        String clientId = "test-client";
        String message = "JWT claim parsing failed";
        JwtClaimParsingException exception = new JwtClaimParsingException(message, clientId);

        // Act
        ResponseEntity<ErrorResponse> response =
                exceptionHandler.handleAuthenticationProcessingException(exception, webRequest);

        // Assert
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        ErrorResponse errorResponse = response.getBody();
        assertNotNull(errorResponse);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), errorResponse.getStatus());
        assertTrue(errorResponse.getMessage().contains(message));
        assertNotNull(errorResponse.getErrorId());
    }

    @Test
    void handleAuthorizationDeniedException_shouldReturnForbiddenStatus() {
        // Arrange
        AuthorizationDeniedException exception = new AuthorizationDeniedException("Access Denied");

        // Act
        ResponseEntity<ErrorResponse> response =
                exceptionHandler.handleAuthorizationDeniedException(exception, webRequest);

        // Assert
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        ErrorResponse errorResponse = response.getBody();
        assertNotNull(errorResponse);
        assertEquals(HttpStatus.FORBIDDEN.value(), errorResponse.getStatus());
        assertTrue(errorResponse.getMessage().contains("insufficient permissions"));
        assertNotNull(errorResponse.getErrorId());
    }

    @Test
    void handleRuntimeException_shouldReturnInternalServerErrorStatus() {
        // Arrange
        String message = "Something went wrong";
        RuntimeException exception = new RuntimeException(message);

        // Act
        ResponseEntity<ErrorResponse> response = exceptionHandler.handleRuntimeException(exception, webRequest);

        // Assert
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        ErrorResponse errorResponse = response.getBody();
        assertNotNull(errorResponse);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), errorResponse.getStatus());
        assertEquals("An internal server error occurred", errorResponse.getMessage());
        assertNotNull(errorResponse.getErrorId());
    }

    @Test
    void handleAllExceptions_shouldReturnInternalServerErrorStatus() {
        // Arrange
        String message = "Generic exception";
        Exception exception = new Exception(message);

        // Act
        ResponseEntity<ErrorResponse> response = exceptionHandler.handleAllExceptions(exception, webRequest);

        // Assert
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        ErrorResponse errorResponse = response.getBody();
        assertNotNull(errorResponse);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), errorResponse.getStatus());
        assertEquals("An unexpected error occurred", errorResponse.getMessage());
        assertNotNull(errorResponse.getErrorId());
    }

    @Test
    void handleNoResourceFoundException_shouldReturnNotFoundStatus() {
        // Arrange
        String path = "/api/v1/invalid";
        NoResourceFoundException exception = new NoResourceFoundException(HttpMethod.GET, path);

        // Act
        ResponseEntity<ErrorResponse> response = exceptionHandler.handleNoResourceFoundException(exception, webRequest);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        ErrorResponse errorResponse = response.getBody();
        assertNotNull(errorResponse);
        assertEquals(HttpStatus.NOT_FOUND.value(), errorResponse.getStatus());
        assertEquals("Resource not found: " + path, errorResponse.getMessage());
        assertNotNull(errorResponse.getErrorId());
    }
}
