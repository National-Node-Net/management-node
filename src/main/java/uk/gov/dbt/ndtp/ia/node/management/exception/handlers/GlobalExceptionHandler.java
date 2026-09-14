/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception.handlers;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import uk.gov.dbt.ndtp.ia.node.management.exception.AuthenticationProcessingException;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateSigningException;
import uk.gov.dbt.ndtp.ia.node.management.exception.ErrorResponse;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiException;

/**
 * Global exception handler for the application.
 * Handles all exceptions thrown by controllers and provides appropriate responses
 * without exposing stack traces to clients.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Generates a unique error ID for tracking and correlation.
     *
     * @return a unique UUID string
     */
    private String generateErrorId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Handles AuthenticationProcessingException and its subclasses.
     *
     * @param ex      the exception
     * @param request the current request
     * @return a ResponseEntity with an error message
     */
    @ExceptionHandler(AuthenticationProcessingException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationProcessingException(
            AuthenticationProcessingException ex, WebRequest request) {

        String errorId = generateErrorId();
        log.debug(
                "Authentication processing exception occurred for client {}, error_id={} , path={}: ",
                ex.getClientId(),
                errorId,
                request.getContextPath(),
                ex);

        ErrorResponse errorResponse =
                new ErrorResponse(HttpStatus.UNAUTHORIZED.value(), "Authentication error: " + ex.getMessage(), errorId);

        return new ResponseEntity<>(errorResponse, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAuthorizationDeniedException(
            AuthorizationDeniedException ex, WebRequest request) {

        String errorId = generateErrorId();
        log.debug("Access denied, error_id={}, path={}: {}", errorId, request.getDescription(false), ex.getMessage());

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(), "Access denied: insufficient permissions for this operation", errorId);

        return new ResponseEntity<>(errorResponse, HttpStatus.FORBIDDEN);
    }

    /**
     * Handles NoResourceFoundException.
     *
     * @param ex      the exception
     * @param request the current request
     * @return a ResponseEntity with a 404 error message
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFoundException(
            NoResourceFoundException ex, WebRequest request) {

        String errorId = generateErrorId();
        log.debug("Resource not found, error_id={}, path={}: ", errorId, request.getContextPath(), ex);

        ErrorResponse errorResponse =
                new ErrorResponse(HttpStatus.NOT_FOUND.value(), "Resource not found: " + ex.getResourcePath(), errorId);

        return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
    }

    /**
     * Handles CertificateSigningException.
     *
     * @param ex      the exception
     * @param request the current request
     * @return a ResponseEntity with a 403 error message
     */
    @ExceptionHandler(CertificateSigningException.class)
    public ResponseEntity<ErrorResponse> handleCertificateSigningException(
            CertificateSigningException ex, WebRequest request) {

        String errorId = generateErrorId();
        log.warn(
                "Certificate signing rejected, error_id={}, path={}: {}",
                errorId,
                request.getDescription(false),
                ex.getMessage());

        ErrorResponse errorResponse = new ErrorResponse(HttpStatus.FORBIDDEN.value(), ex.getMessage(), errorId);

        return new ResponseEntity<>(errorResponse, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(PkiException.class)
    public ResponseEntity<ErrorResponse> handlePkiException(PkiException ex, WebRequest request) {

        String errorId = generateErrorId();
        log.error(
                "PKI exception occurred, error_id={}, path={}: {}",
                errorId,
                request.getDescription(false),
                ex.getMessage(),
                ex);

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(), "PKI/Certificate error: " + ex.getMessage(), errorId);

        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Handles {@code @Valid} request body validation failures (e.g. field size/blank
     * constraints) with a 400, rather than falling through to the 500 handler below.
     *
     * @param ex      the exception
     * @param request the current request
     * @return a ResponseEntity with a 400 error message
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException ex, WebRequest request) {

        String errorId = generateErrorId();
        log.debug(
                "Request validation failed, error_id={}, path={}: {}",
                errorId,
                request.getDescription(false),
                ex.getMessage());

        ErrorResponse errorResponse =
                new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Invalid request: " + ex.getMessage(), errorId);

        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles malformed/unreadable request bodies (e.g. invalid JSON, wrong field types)
     * with a 400, rather than falling through to the 500 handler below.
     *
     * @param ex      the exception
     * @param request the current request
     * @return a ResponseEntity with a 400 error message
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException ex, WebRequest request) {

        String errorId = generateErrorId();
        log.debug(
                "Malformed request body, error_id={}, path={}: {}",
                errorId,
                request.getDescription(false),
                ex.getMessage());

        ErrorResponse errorResponse =
                new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Invalid request body", errorId);

        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles RuntimeException.
     *
     * @param ex      the exception
     * @param request the current request
     * @return a ResponseEntity with an error message
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntimeException(RuntimeException ex, WebRequest request) {

        String errorId = generateErrorId();
        log.debug("Runtime exception occurred, error_id={}, path={}: ", errorId, request.getContextPath(), ex);

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(), "An internal server error occurred", errorId);

        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Handles all other exceptions.
     *
     * @param ex      the exception
     * @param request the current request
     * @return a ResponseEntity with an error message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAllExceptions(Exception ex, WebRequest request) {

        String errorId = generateErrorId();
        log.debug("Runtime exception occurred, error_id={}, path={}: ", errorId, request.getContextPath(), ex);

        ErrorResponse errorResponse =
                new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "An unexpected error occurred", errorId);

        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
