/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;

class PolicyBodyCachingFilterTest {

    private static final String BODY =
            """
            {"text": "this is sample text", "filters": {"key 1": "value", "key 2": [1234, 33, 222]}}""";

    @RestController
    static class SampleController {

        @Policy(resource = "product", action = "subscribe")
        @PostMapping("/api/v1/product/subscribe")
        public void subscribe(@RequestBody String body) {}

        @PostMapping("/api/v1/product/discover")
        public void discover(@RequestBody String body) {}
    }

    private RequestMappingHandlerMapping handlerMapping;

    /** A real mapping, so the lookup is exercised as the dispatcher would perform it. */
    @BeforeEach
    void setUp() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.registerSingleton("sampleController", SampleController.class);
        context.refresh();
        handlerMapping = new RequestMappingHandlerMapping();
        handlerMapping.setApplicationContext(context);
        handlerMapping.afterPropertiesSet();
    }

    private static OpaProperties properties(boolean enabled) {
        return new OpaProperties(
                enabled,
                "http://opa:8181",
                "/v1/data/dispatch/decision",
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                List.of("content-type"),
                false,
                false);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RequestMappingHandlerMapping> providerOf(RequestMappingHandlerMapping... mappings) {
        ObjectProvider<RequestMappingHandlerMapping> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(mappings));
        return provider;
    }

    private PolicyBodyCachingFilter filter(boolean enabled) {
        return new PolicyBodyCachingFilter(properties(enabled), providerOf(handlerMapping));
    }

    private static MockHttpServletRequest jsonPost(String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    /** Captures what the rest of the chain was handed, since that is what the handler binds. */
    private static HttpServletRequest runFilter(PolicyBodyCachingFilter filter, MockHttpServletRequest request)
            throws Exception {
        AtomicReference<HttpServletRequest> downstream = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                downstream.set((HttpServletRequest) req);
            }
        };
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return downstream.get();
    }

    @Test
    void policyHandlerWithJsonBody_isBufferedAndStillReadableDownstream() throws Exception {
        HttpServletRequest downstream = runFilter(filter(true), jsonPost("/api/v1/product/subscribe", BODY));

        assertThat(downstream).isInstanceOf(CachedBodyHttpServletRequest.class);
        // The handler must still see the body: buffering it for policy cannot consume it.
        assertThat(StreamUtils.copyToString(downstream.getInputStream(), StandardCharsets.UTF_8))
                .isEqualTo(BODY);
        // ... and more than once, since the PEP reads it before the handler does.
        assertThat(StreamUtils.copyToString(downstream.getInputStream(), StandardCharsets.UTF_8))
                .isEqualTo(BODY);
    }

    /** The dispatcher resolves the handler afresh, so the lookup must leave no trace behind. */
    @Test
    void handlerLookup_leavesNoAttributesOnTheRequest() throws Exception {
        MockHttpServletRequest request = jsonPost("/api/v1/product/subscribe", BODY);

        runFilter(filter(true), request);

        assertThat(request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE))
                .isNull();
        assertThat(request.getAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE)).isNull();
    }

    @Test
    void handlerWithoutPolicy_isNotBuffered() throws Exception {
        HttpServletRequest downstream = runFilter(filter(true), jsonPost("/api/v1/product/discover", BODY));

        assertThat(downstream).isNotInstanceOf(CachedBodyHttpServletRequest.class);
    }

    @Test
    void noMatchingHandler_isNotBuffered() throws Exception {
        HttpServletRequest downstream = runFilter(filter(true), jsonPost("/api/v1/unmapped", BODY));

        assertThat(downstream).isNotInstanceOf(CachedBodyHttpServletRequest.class);
    }

    @Test
    void handlerLookupThrowing_passesTheRequestThroughUnbuffered() throws Exception {
        RequestMappingHandlerMapping failing = mock(RequestMappingHandlerMapping.class);
        when(failing.getHandler(any())).thenThrow(new IllegalStateException("mapping not ready"));
        PolicyBodyCachingFilter filter = new PolicyBodyCachingFilter(properties(true), providerOf(failing));
        MockHttpServletRequest request = jsonPost("/api/v1/product/subscribe", BODY);

        HttpServletRequest downstream = runFilter(filter, request);

        assertThat(downstream).isSameAs(request);
        assertThat(StreamUtils.copyToString(downstream.getInputStream(), StandardCharsets.UTF_8))
                .isEqualTo(BODY);
    }

    @Test
    void mappingsUnavailable_passesTheRequestThroughUnbuffered() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<RequestMappingHandlerMapping> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenThrow(new IllegalStateException("context closing"));
        MockHttpServletRequest request = jsonPost("/api/v1/product/subscribe", BODY);

        HttpServletRequest downstream = runFilter(new PolicyBodyCachingFilter(properties(true), provider), request);

        assertThat(downstream).isSameAs(request);
    }

    @Test
    void opaDisabled_isNotBufferedAndNoHandlerIsLookedUp() throws Exception {
        ObjectProvider<RequestMappingHandlerMapping> provider = providerOf(handlerMapping);

        HttpServletRequest downstream = runFilter(
                new PolicyBodyCachingFilter(properties(false), provider), jsonPost("/api/v1/product/subscribe", BODY));

        assertThat(downstream).isNotInstanceOf(CachedBodyHttpServletRequest.class);
        verifyNoInteractions(provider);
    }

    @Test
    void bodylessRequest_isNotBuffered() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/product/subscribe");

        HttpServletRequest downstream = runFilter(filter(true), request);

        assertThat(downstream).isNotInstanceOf(CachedBodyHttpServletRequest.class);
    }

    /** Reading part of an oversized body to measure it would corrupt the stream, so it is left alone. */
    @Test
    void bodyAboveTheLimit_isStreamedThroughUntouched() throws Exception {
        String oversized = "\"" + "x".repeat(PolicyBodyCachingFilter.MAX_BODY_BYTES) + "\"";

        HttpServletRequest downstream = runFilter(filter(true), jsonPost("/api/v1/product/subscribe", oversized));

        assertThat(downstream).isNotInstanceOf(CachedBodyHttpServletRequest.class);
        assertThat(StreamUtils.copyToString(downstream.getInputStream(), StandardCharsets.UTF_8))
                .isEqualTo(oversized);
    }

    @Test
    void cachedRequest_replaysTheBodyThroughTheReaderToo() throws Exception {
        HttpServletRequest downstream = runFilter(filter(true), jsonPost("/api/v1/product/subscribe", BODY));

        assertThat(downstream.getReader().lines().reduce("", String::concat)).isEqualTo(BODY);
    }
}
