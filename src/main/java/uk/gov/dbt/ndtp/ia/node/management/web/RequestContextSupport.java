/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The servlet request a controller method is being invoked for. Method-level enforcement runs
 * inside the handler call rather than around the request, so it reaches the request through the
 * holder {@code DispatcherServlet} binds for the duration of the dispatch.
 */
public final class RequestContextSupport {

    private RequestContextSupport() {}

    /** The current request, or empty when the call is not part of a servlet request. */
    public static Optional<HttpServletRequest> currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servletAttributes
                ? Optional.of(servletAttributes.getRequest())
                : Optional.empty();
    }
}
