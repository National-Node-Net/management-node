/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception.handlers;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import uk.gov.dbt.ndtp.ia.node.management.exception.AccessRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.AuthenticationProcessingException;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateSigningException;
import uk.gov.dbt.ndtp.ia.node.management.exception.ErrorResponse;
import uk.gov.dbt.ndtp.ia.node.management.exception.InvalidSearchCriteriaException;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiException;

/**
 * Turns every exception a controller throws into an {@link ErrorResponse}: a status, a message a
 * caller can act on, and an error id.
 *
 * <p>The response never carries framework or code details - no class names, method signatures,
 * rejected values or exception messages from libraries. The full exception, stack trace included,
 * is logged instead under the same error id, so a response can be traced to its log line:
 * {@code ERROR} for 5xx, {@code WARN} for a bad request, and {@code DEBUG} for authentication and
 * authorisation refusals, which are routine and logged where they are decided.
 *
 * <p>Spring MVC's own exceptions (validation, unreadable bodies, type mismatches, missing
 * parameters, unsupported methods and media types, unknown paths, ...) are mapped to their status
 * by {@link ResponseEntityExceptionHandler}; {@link #handleExceptionInternal} replaces its body
 * with an {@link ErrorResponse} and a message from {@link #clientMessage}.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String INTERNAL_ERROR_MESSAGE = "An internal server error occurred";

    // ---------------------------------------------------------------------------------------
    // Application exceptions
    // ---------------------------------------------------------------------------------------

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
                "Authentication processing exception occurred for client {}, error_id={}, path={}",
                ex.getClientId(),
                errorId,
                request.getDescription(false),
                ex);

        return respond(HttpStatus.UNAUTHORIZED, "Authentication error: " + ex.getMessage(), errorId);
    }

    /**
     * Handles a request refused by the certificate check or the policy decision, which run only
     * once method security has authorised the caller.
     *
     * @param ex the rejection, carrying the message and reasons for the caller and the logged error id
     * @param request the current request
     * @return a 403 with the rejection's message, reasons and error id
     */
    @ExceptionHandler(AccessRejectedException.class)
    public ResponseEntity<ErrorResponse> handleAccessRejectedException(AccessRejectedException ex, WebRequest request) {
        log.debug("Access rejected, error_id={}, path={}", ex.getErrorId(), request.getDescription(false), ex);
        ErrorResponse errorResponse =
                new ErrorResponse(HttpStatus.FORBIDDEN.value(), ex.getMessage(), ex.getReasons(), ex.getErrorId());
        return new ResponseEntity<>(errorResponse, HttpStatus.FORBIDDEN);
    }

    /**
     * Handles search criteria the service cannot act on - a filter naming both a field and an
     * attribute, an operator that does not apply to its target, a value of the wrong type. The
     * message names what the caller sent, since they supplied it and have to correct it.
     *
     * <p>Criteria the caller is not <em>permitted</em> to use are a different answer: those are an
     * {@link AccessRejectedException} and a {@code 403}, so that a name which does not exist is
     * refused exactly like one that is forbidden.
     *
     * @param ex the rejection, carrying the message for the caller
     * @param request the current request
     * @return a 400 with that message and an error id
     */
    @ExceptionHandler(InvalidSearchCriteriaException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSearchCriteriaException(
            InvalidSearchCriteriaException ex, WebRequest request) {

        String errorId = generateErrorId();
        log.warn("Invalid search criteria, error_id={}, path={}", errorId, request.getDescription(false), ex);

        return respond(HttpStatus.BAD_REQUEST, "Invalid search criteria: " + ex.getMessage(), errorId);
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAuthorizationDeniedException(
            AuthorizationDeniedException ex, WebRequest request) {

        String errorId = generateErrorId();
        log.debug("Access denied, error_id={}, path={}", errorId, request.getDescription(false), ex);

        return respond(HttpStatus.FORBIDDEN, "Access denied: insufficient permissions for this operation", errorId);
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
        log.warn("Certificate signing rejected, error_id={}, path={}", errorId, request.getDescription(false), ex);

        return respond(HttpStatus.FORBIDDEN, ex.getMessage(), errorId);
    }

    @ExceptionHandler(PkiException.class)
    public ResponseEntity<ErrorResponse> handlePkiException(PkiException ex, WebRequest request) {

        String errorId = generateErrorId();
        log.error("PKI exception occurred, error_id={}, path={}", errorId, request.getDescription(false), ex);

        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "PKI/Certificate error: " + ex.getMessage(), errorId);
    }

    /**
     * Handles RuntimeException not handled more specifically.
     *
     * @param ex      the exception
     * @param request the current request
     * @return a ResponseEntity with an error message
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntimeException(RuntimeException ex, WebRequest request) {

        String errorId = generateErrorId();
        log.error("Unhandled exception, error_id={}, path={}", errorId, request.getDescription(false), ex);

        return respond(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_MESSAGE, errorId);
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
        log.error("Unhandled exception, error_id={}, path={}", errorId, request.getDescription(false), ex);

        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", errorId);
    }

    // ---------------------------------------------------------------------------------------
    // Spring MVC exceptions
    // ---------------------------------------------------------------------------------------

    /**
     * Every Spring MVC exception handled by {@link ResponseEntityExceptionHandler} ends here, with
     * the status and headers (e.g. {@code Allow}) it chose. Logs the exception in full and answers
     * with an {@link ErrorResponse} instead of the framework's problem detail.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {

        String errorId = generateErrorId();
        if (statusCode.is5xxServerError()) {
            log.error(
                    "Request failed with {}, error_id={}, path={}",
                    statusCode.value(),
                    errorId,
                    request.getDescription(false),
                    ex);
        } else {
            log.warn(
                    "Request rejected with {}, error_id={}, path={}",
                    statusCode.value(),
                    errorId,
                    request.getDescription(false),
                    ex);
        }
        ErrorResponse errorResponse = new ErrorResponse(statusCode.value(), clientMessage(ex, statusCode), errorId);
        return super.handleExceptionInternal(ex, errorResponse, headers, statusCode, request);
    }

    /**
     * The message a caller sees for a Spring MVC exception. Names only what the caller sent - a
     * field, parameter, header, method or content type - and the constraint it broke, never the
     * value, the handler or the exception's own message.
     */
    static String clientMessage(Exception ex, HttpStatusCode statusCode) {
        return switch (ex) {
            case MethodArgumentNotValidException e -> "Invalid request: " + describe(e.getBindingResult());
            case HandlerMethodValidationException e -> "Invalid request: " + describe(e);
            case HttpMessageNotReadableException e -> "Invalid request body: " + describe(e);
            case MethodArgumentTypeMismatchException e -> "Invalid value for '" + e.getName() + "'";
            case TypeMismatchException e -> "Invalid value for '" + e.getPropertyName() + "'";
            case MissingServletRequestParameterException e ->
                "Missing required parameter '" + e.getParameterName() + "'";
            case MissingRequestHeaderException e -> "Missing required header '" + e.getHeaderName() + "'";
            case HttpRequestMethodNotSupportedException e ->
                "HTTP method " + e.getMethod() + " is not supported for this endpoint";
            case HttpMediaTypeNotSupportedException e ->
                e.getContentType() == null
                        ? "Content type is not supported"
                        : "Content type '" + e.getContentType() + "' is not supported";
            case HttpMediaTypeNotAcceptableException e -> "The requested response format is not supported";
            case NoResourceFoundException e -> "Resource not found: " + e.getResourcePath();
            case NoHandlerFoundException e -> "Resource not found: " + e.getRequestURL();
            case MaxUploadSizeExceededException e -> "Request is too large";
            default -> statusCode.is5xxServerError() ? INTERNAL_ERROR_MESSAGE : reasonPhrase(statusCode);
        };
    }

    /** Field errors as "productId must not be null", object errors as their message alone. */
    private static String describe(BindingResult bindingResult) {
        Set<String> problems = new LinkedHashSet<>();
        bindingResult.getFieldErrors().forEach(error -> problems.add(describe(error)));
        bindingResult.getGlobalErrors().forEach(error -> problems.add(error.getDefaultMessage()));
        return join(problems);
    }

    /** Constraint violations on handler parameters, e.g. an {@code @Size} on a path variable. */
    private static String describe(HandlerMethodValidationException ex) {
        Set<String> problems = new LinkedHashSet<>();
        ex.getParameterValidationResults().forEach(result -> {
            String parameter = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                problems.add(
                        error instanceof FieldError fieldError
                                ? describe(fieldError)
                                : (parameter == null ? "" : parameter + " ") + error.getDefaultMessage());
            }
        });
        return join(problems);
    }

    /**
     * Why a body could not be read, in the caller's terms: missing, not JSON, or a field of the
     * wrong type, named by its path (e.g. {@code filters.key}).
     */
    private static String describe(HttpMessageNotReadableException ex) {
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof JsonParseException) {
                return "malformed JSON";
            }
            if (cause instanceof JsonMappingException mapping
                    && !mapping.getPath().isEmpty()) {
                return "'" + path(mapping) + "' has an invalid value";
            }
        }
        String message = ex.getMessage();
        if (message != null && message.startsWith("Required request body is missing")) {
            return "request body is missing";
        }
        return "the body could not be read";
    }

    private static String describe(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }

    private static String path(JsonMappingException ex) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : ex.getPath()) {
            if (reference.getFieldName() != null) {
                path.append(path.isEmpty() ? "" : ".").append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    private static String join(Set<String> problems) {
        return problems.isEmpty()
                ? "the request is not valid"
                : problems.stream().collect(Collectors.joining("; "));
    }

    private static String reasonPhrase(HttpStatusCode statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status == null ? "Request could not be processed" : status.getReasonPhrase();
    }

    // ---------------------------------------------------------------------------------------

    private static ResponseEntity<ErrorResponse> respond(HttpStatus status, String message, String errorId) {
        return new ResponseEntity<>(new ErrorResponse(status.value(), message, errorId), status);
    }

    /**
     * Generates a unique error ID for tracking and correlation.
     *
     * @return a unique UUID string
     */
    private static String generateErrorId() {
        return UUID.randomUUID().toString();
    }
}
