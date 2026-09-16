/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.policy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;

/**
 * Buffers the request body on policy-protected paths so the Policy Enforcement Point can send it
 * to the PDP and the handler can still bind it. Without this the PEP has no body to pass:
 * {@code preHandle} runs before the handler binds anything, and reading the one-shot stream there
 * would leave the handler nothing to read.
 *
 * <p>Deliberately narrow, since buffering a body costs memory that streaming does not:
 *
 * <ul>
 *   <li>only when policy enforcement is on - with OPA off no decision is made, so no body is needed
 *   <li>only on {@code application.opa.protected-paths} - product discovery is not among them and
 *       needs no buffer, because its controller already has the body bound
 *   <li>only for a declared content length of at most {@value #MAX_BODY_BYTES} bytes. A larger or
 *       undeclared (chunked) body is passed straight through unbuffered: partially reading it to
 *       find out how big it is would corrupt the very stream this filter exists to preserve.
 * </ul>
 */
@Component
@Slf4j
public class PolicyBodyCachingFilter extends OncePerRequestFilter {

    /** Upper bound on a buffered body. Anything larger is streamed through untouched. */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final boolean enabled;
    private final List<String> protectedPaths;

    public PolicyBodyCachingFilter(OpaProperties opaProperties) {
        this.enabled = opaProperties.enabled();
        this.protectedPaths = opaProperties.protectedPaths() == null ? List.of() : opaProperties.protectedPaths();
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
                path(request));
        filterChain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
    }

    private boolean shouldCache(HttpServletRequest request) {
        if (!enabled || !isProtected(path(request))) {
            return false;
        }
        long length = request.getContentLengthLong();
        if (length <= 0) {
            // Zero means no body; a negative length means the sender did not declare one
            // (chunked), and there is no way to bound it without reading it.
            return false;
        }
        if (length > MAX_BODY_BYTES) {
            log.warn(
                    "Body of {} {} is {} bytes, above the {} byte policy buffer limit - the PDP will "
                            + "see no body for this request",
                    request.getMethod(),
                    path(request),
                    length,
                    MAX_BODY_BYTES);
            return false;
        }
        return true;
    }

    private boolean isProtected(String path) {
        return protectedPaths.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null ? "" : uri;
    }
}
