/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.policy;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.StreamUtils;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;

class PolicyBodyCachingFilterTest {

    private static final String BODY =
            """
            {"text": "this is sample text", "filters": {"key 1": "value", "key 2": [1234, 33, 222]}}""";

    private static OpaProperties properties(boolean enabled) {
        return new OpaProperties(
                enabled,
                "http://opa:8181",
                "/v1/data/management_node/decision",
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                List.of("/api/v1/configuration/**"),
                List.of("content-type"),
                false,
                false);
    }

    private static MockHttpServletRequest jsonPost(String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    /** Captures what the rest of the chain was handed, since that is what the handler binds. */
    private static AtomicReference<HttpServletRequest> runFilter(
            PolicyBodyCachingFilter filter, MockHttpServletRequest request) throws Exception {
        AtomicReference<HttpServletRequest> downstream = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                downstream.set((HttpServletRequest) req);
            }
        };
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return downstream;
    }

    @Test
    void protectedPathWithJsonBody_isBufferedAndStillReadableDownstream() throws Exception {
        MockHttpServletRequest request = jsonPost("/api/v1/configuration/producer", BODY);

        HttpServletRequest downstream = runFilter(new PolicyBodyCachingFilter(properties(true)), request)
                .get();

        assertThat(downstream).isInstanceOf(CachedBodyHttpServletRequest.class);
        // The handler must still see the body: buffering it for policy cannot consume it.
        assertThat(StreamUtils.copyToString(downstream.getInputStream(), StandardCharsets.UTF_8))
                .isEqualTo(BODY);
        // ... and more than once, since the PEP reads it before the handler does.
        assertThat(StreamUtils.copyToString(downstream.getInputStream(), StandardCharsets.UTF_8))
                .isEqualTo(BODY);
    }

    @Test
    void unprotectedPath_isNotBuffered() throws Exception {
        MockHttpServletRequest request = jsonPost("/api/v1/product/discover", BODY);

        HttpServletRequest downstream = runFilter(new PolicyBodyCachingFilter(properties(true)), request)
                .get();

        assertThat(downstream).isNotInstanceOf(CachedBodyHttpServletRequest.class);
    }

    @Test
    void opaDisabled_isNotBuffered() throws Exception {
        MockHttpServletRequest request = jsonPost("/api/v1/configuration/producer", BODY);

        HttpServletRequest downstream = runFilter(new PolicyBodyCachingFilter(properties(false)), request)
                .get();

        assertThat(downstream).isNotInstanceOf(CachedBodyHttpServletRequest.class);
    }

    @Test
    void bodylessRequest_isNotBuffered() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/configuration/producer");

        HttpServletRequest downstream = runFilter(new PolicyBodyCachingFilter(properties(true)), request)
                .get();

        assertThat(downstream).isNotInstanceOf(CachedBodyHttpServletRequest.class);
    }

    /** Reading part of an oversized body to measure it would corrupt the stream, so it is left alone. */
    @Test
    void bodyAboveTheLimit_isStreamedThroughUntouched() throws Exception {
        String oversized = "\"" + "x".repeat(PolicyBodyCachingFilter.MAX_BODY_BYTES) + "\"";
        MockHttpServletRequest request = jsonPost("/api/v1/configuration/producer", oversized);

        HttpServletRequest downstream = runFilter(new PolicyBodyCachingFilter(properties(true)), request)
                .get();

        assertThat(downstream).isNotInstanceOf(CachedBodyHttpServletRequest.class);
        assertThat(StreamUtils.copyToString(downstream.getInputStream(), StandardCharsets.UTF_8))
                .isEqualTo(oversized);
    }

    @Test
    void cachedRequest_replaysTheBodyThroughTheReaderToo() throws Exception {
        MockHttpServletRequest request = jsonPost("/api/v1/configuration/producer", BODY);

        HttpServletRequest downstream = runFilter(new PolicyBodyCachingFilter(properties(true)), request)
                .get();

        assertThat(downstream.getReader().lines().reduce("", String::concat)).isEqualTo(BODY);
    }
}
