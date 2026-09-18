/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.policy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;

/**
 * Buffers the request body for handlers carrying {@link Policy}, so the Policy Enforcement Point
 * can send it to the PDP and the handler can still bind it. Without this the PEP has no body to
 * pass: {@code preHandle} runs before the handler binds anything, and reading the one-shot stream
 * there would leave the handler nothing to read.
 *
 * <p>Deliberately narrow, since buffering a body costs memory that streaming does not:
 *
 * <ul>
 *   <li>only when policy enforcement is on - with OPA off no decision is made, so no body is needed
 *   <li>only when the request maps to a handler method annotated with {@link Policy}. A handler
 *       that cannot be resolved - no match, or the lookup itself failing - is treated as
 *       unannotated: the request is passed through, never failed here
 *   <li>only for a declared content length of at most {@value #MAX_BODY_BYTES} bytes. A larger or
 *       undeclared (chunked) body is passed straight through unbuffered: partially reading it to
 *       find out how big it is would corrupt the very stream this filter exists to preserve.
 * </ul>
 *
 * <p>The handler mappings are looked up per request through an {@link ObjectProvider} rather than
 * injected. Filters are instantiated while the servlet context is being initialised, before the
 * MVC infrastructure; injecting the mapping directly would drag it - and everything its
 * interceptors depend on - into that early phase, inviting bean-initialisation cycles.
 */
@Component
@Slf4j
public class PolicyBodyCachingFilter extends OncePerRequestFilter {

    /** Upper bound on a buffered body. Anything larger is streamed through untouched. */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    private final boolean enabled;
    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappings;

    public PolicyBodyCachingFilter(
            OpaProperties opaProperties, ObjectProvider<RequestMappingHandlerMapping> handlerMappings) {
        this.enabled = opaProperties.enabled();
        this.handlerMappings = handlerMappings;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!shouldCache(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        log.debug(
                "Buffered {} byte body of {} {} for policy evaluation",
                body.length,
                request.getMethod(),
                request.getRequestURI());
        filterChain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
    }

    private boolean shouldCache(HttpServletRequest request) {
        if (!enabled) {
            return false;
        }
        long length = request.getContentLengthLong();
        if (length <= 0) {
            // Zero means no body; a negative length means the sender did not declare one
            // (chunked), and there is no way to bound it without reading it.
            return false;
        }
        if (!isPolicyHandler(request)) {
            return false;
        }
        if (length > MAX_BODY_BYTES) {
            log.warn(
                    "Body of {} {} is {} bytes, above the {} byte policy buffer limit - the PDP will "
                            + "see no body for this request",
                    request.getMethod(),
                    request.getRequestURI(),
                    length,
                    MAX_BODY_BYTES);
            return false;
        }
        return true;
    }

    private boolean isPolicyHandler(HttpServletRequest request) {
        try {
            // The lookup records its match as request attributes, and needs the parsed path the
            // DispatcherServlet has not yet cached. Both are kept off the real request, so the
            // dispatcher later resolves the handler from a clean slate.
            HttpServletRequest lookup = new AttributeIsolatingRequest(request);
            ServletRequestPathUtils.parseAndCache(lookup);
            return handlerMappings
                    .orderedStream()
                    .map(mapping -> handlerOf(mapping, lookup))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .map(handler -> handler instanceof HandlerMethod method && method.hasMethodAnnotation(Policy.class))
                    .orElse(false);
        } catch (Exception e) {
            log.debug(
                    "Could not resolve the handler for {} {}; not buffering its body",
                    request.getMethod(),
                    request.getRequestURI(),
                    e);
            return false;
        }
    }

    private static Object handlerOf(RequestMappingHandlerMapping mapping, HttpServletRequest request) {
        try {
            HandlerExecutionChain chain = mapping.getHandler(request);
            return chain == null ? null : chain.getHandler();
        } catch (Exception e) {
            throw new IllegalStateException("Handler lookup failed", e);
        }
    }

    /** Reads through to the wrapped request's attributes but keeps every write to itself. */
    private static final class AttributeIsolatingRequest extends HttpServletRequestWrapper {

        private final Map<String, Object> written = new HashMap<>();
        private final Set<String> removed = new HashSet<>();

        AttributeIsolatingRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public Object getAttribute(String name) {
            if (written.containsKey(name)) {
                return written.get(name);
            }
            return removed.contains(name) ? null : super.getAttribute(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            if (value == null) {
                removeAttribute(name);
                return;
            }
            removed.remove(name);
            written.put(name, value);
        }

        @Override
        public void removeAttribute(String name) {
            written.remove(name);
            removed.add(name);
        }
    }
}
