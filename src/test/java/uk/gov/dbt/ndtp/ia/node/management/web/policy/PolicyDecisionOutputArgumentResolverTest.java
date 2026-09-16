/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.DefaultPolicyDecisionOutput;

/**
 * Covers how a controller is given the PDP's decision: present only when policy enforcement is on
 * and a decision was published for the request, empty in every other case.
 */
class PolicyDecisionOutputArgumentResolverTest {

    private static final DefaultPolicyDecisionOutput PUBLISHED =
            new DefaultPolicyDecisionOutput(true, List.of("name"), List.of("internal_owner"), List.of("email"));

    @SuppressWarnings("unused")
    void handler(
            Optional<DefaultPolicyDecisionOutput> decision,
            DefaultPolicyDecisionOutput unwrapped,
            Optional<String> otherOptional) {}

    private static MethodParameter parameter(int index) throws NoSuchMethodException {
        Method method = PolicyDecisionOutputArgumentResolverTest.class.getDeclaredMethod(
                "handler", Optional.class, DefaultPolicyDecisionOutput.class, Optional.class);
        return new MethodParameter(method, index);
    }

    private static PolicyDecisionOutputArgumentResolver resolver(boolean opaEnabled) {
        return new PolicyDecisionOutputArgumentResolver(new OpaProperties(
                opaEnabled,
                "http://localhost:8181",
                "/v1/data/management_node/decision",
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                List.of("/api/v1/configuration/**"),
                List.of("content-type"),
                false,
                false));
    }

    private static Optional<DefaultPolicyDecisionOutput> resolve(boolean opaEnabled, MockHttpServletRequest request)
            throws Exception {
        return resolver(opaEnabled).resolveArgument(parameter(0), null, new ServletWebRequest(request), null);
    }

    private static MockHttpServletRequest requestWithPublishedDecision() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DefaultPolicyDecisionOutput.REQUEST_ATTRIBUTE, PUBLISHED);
        return request;
    }

    @Test
    void supportsOnlyAnOptionalDecisionParameter() throws Exception {
        PolicyDecisionOutputArgumentResolver resolver = resolver(true);

        assertThat(resolver.supportsParameter(parameter(0))).isTrue();
        // One way to ask for the decision, so every handler has to deal with its absence.
        assertThat(resolver.supportsParameter(parameter(1))).isFalse();
        assertThat(resolver.supportsParameter(parameter(2))).isFalse();
    }

    @Test
    void opaEnabled_publishedDecision_isHandedToTheController() throws Exception {
        assertThat(resolve(true, requestWithPublishedDecision())).containsSame(PUBLISHED);
    }

    /** Enabled, but no decision was taken for this request - e.g. a path outside protected-paths. */
    @Test
    void opaEnabled_noPublishedDecision_isEmpty() throws Exception {
        assertThat(resolve(true, new MockHttpServletRequest())).isEmpty();
    }

    /** With policy off there is no decision to give, whatever happens to be on the request. */
    @Test
    void opaDisabled_isEmpty_evenIfADecisionWasPublished() throws Exception {
        assertThat(resolve(false, requestWithPublishedDecision())).isEmpty();
        assertThat(resolve(false, new MockHttpServletRequest())).isEmpty();
    }

    @Test
    void attributeOfAnotherType_isIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DefaultPolicyDecisionOutput.REQUEST_ATTRIBUTE, "ALLOW");

        assertThat(resolve(true, request)).isEmpty();
    }
}
