/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.Function;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import uk.gov.dbt.ndtp.ia.node.management.exception.ErrorResponse;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;

/**
 * Shared request-rejection behaviour for {@code HandlerInterceptor}s that gate access
 * on the authenticated client: resolving the client id from the security context and
 * writing a JSON {@link ErrorResponse} for a rejected request. Shared by the interceptors in
 * the sibling packages, which gate different things on the same identity.
 */
public final class RequestRejectionSupport {

    private RequestRejectionSupport() {}

    public static String extractClientId() {
        return fromPrincipal(EnhancedPrincipal::clientId);
    }

    private static String fromPrincipal(Function<EnhancedPrincipal, String> accessor) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof EnhancedPrincipal principal)) {
            return null;
        }
        String value = accessor.apply(principal);
        return (value == null || value.isEmpty()) ? null : value;
    }

    public static void writeError(
            HttpServletResponse response, ObjectMapper objectMapper, int status, String message, String errorId)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse errorResponse = new ErrorResponse(status, message, errorId);
        objectMapper.writeValue(response.getWriter(), errorResponse);
    }
}
