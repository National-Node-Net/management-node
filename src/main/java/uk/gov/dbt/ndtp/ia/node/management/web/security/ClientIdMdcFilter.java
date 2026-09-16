/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import uk.gov.dbt.ndtp.ia.node.management.model.UnknownIdentifiers;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;

/**
 * Filter that adds the clientId from the Authentication object to the MDC context.
 * This allows the clientId to be included in all log messages.
 * This filter should be registered to run after the BearerTokenAuthenticationFilter
 * to ensure that the Authentication object is already set in the SecurityContext.
 * If the Authentication object is null or does not contain an EnhancedPrincipal,
 * the filter logs the clientId as {@code UNKNOWN_CLIENT}.
 */
@Component
@Slf4j
public class ClientIdMdcFilter extends OncePerRequestFilter {

    public static final String CLIENT_ID_MDC_KEY = "clientId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            log.trace("ClientIdMdcFilter processing request: {}", request.getRequestURI());

            // Get the Authentication object from the SecurityContext
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            if (authentication == null) {
                log.trace("Authentication is null in SecurityContextHolder for request: {}", request.getRequestURI());
            } else {
                log.trace(
                        "Authentication found in SecurityContextHolder: {}, Principal type: {}",
                        authentication.getName(),
                        authentication.getPrincipal() != null
                                ? authentication.getPrincipal().getClass().getName()
                                : "null");
            }

            // Extract clientId from the Authentication object if possible
            String clientId = extractClientId(authentication);

            // Put the clientId in the MDC context
            MDC.put(CLIENT_ID_MDC_KEY, clientId);

            log.trace("Added clientId to MDC: {}", clientId);

            // Continue with the filter chain
            filterChain.doFilter(request, response);
        } finally {
            // Always clear the MDC context to prevent memory leaks
            MDC.remove(CLIENT_ID_MDC_KEY);
            log.trace("Removed clientId from MDC");
        }
    }

    /**
     * Extracts the clientId from the Authentication object.
     * <p>
     * This method checks if the Authentication object is not null and if its principal
     * is an instance of EnhancedPrincipal. If so, it extracts the clientId from the
     * EnhancedPrincipal. Otherwise, it returns {@code UNKNOWN_CLIENT}.
     * <p>
     * Debug logging is included to help diagnose issues with the Authentication object
     * and its principal.
     *
     * @param authentication the Authentication object
     * @return the clientId, or {@code UNKNOWN_CLIENT} if it cannot be determined
     */
    private String extractClientId(Authentication authentication) {
        if (authentication != null) {
            log.trace(
                    "Authentication found: {}, Principal type: {}",
                    authentication.getName(),
                    authentication.getPrincipal() != null
                            ? authentication.getPrincipal().getClass().getName()
                            : "null");

            Object principal = authentication.getPrincipal();

            if (principal instanceof EnhancedPrincipal enhancedPrincipal) {
                String clientId = enhancedPrincipal.clientId();
                return clientId != null && !clientId.isEmpty() ? clientId : UnknownIdentifiers.UNKNOWN_CLIENT;
            } else {
                log.warn(
                        "Principal is not an instance of EnhancedPrincipal: {}",
                        principal != null ? principal.getClass().getName() : "null");
            }
        } else {
            log.trace("Authentication is null in SecurityContextHolder");
        }
        return UnknownIdentifiers.UNKNOWN_CLIENT;
    }
}
