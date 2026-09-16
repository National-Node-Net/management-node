/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.DefaultPolicyDecisionOutput;

/**
 * Covers how a controller is given the PDP's decision: the one the Policy Enforcement Point
 * published for this request, and what a handler on an unprotected path gets instead.
 */
class PolicyDecisionOutputArgumentResolverTest {

    private final PolicyDecisionOutputArgumentResolver resolver = new PolicyDecisionOutputArgumentResolver();

    @SuppressWarnings("unused")
    void handler(DefaultPolicyDecisionOutput decision, String other) {}

    private MethodParameter parameter(int index) throws NoSuchMethodException {
        Method method = getClass().getDeclaredMethod("handler", DefaultPolicyDecisionOutput.class, String.class);
        return new MethodParameter(method, index);
    }

    private DefaultPolicyDecisionOutput resolve(MockHttpServletRequest request) throws Exception {
        return resolver.resolveArgument(parameter(0), null, new ServletWebRequest(request), null);
    }

    @Test
    void supportsOnlyTheDecisionParameter() throws Exception {
        assertThat(resolver.supportsParameter(parameter(0))).isTrue();
        assertThat(resolver.supportsParameter(parameter(1))).isFalse();
    }

    @Test
    void publishedDecision_isHandedToTheController() throws Exception {
        DefaultPolicyDecisionOutput published =
                new DefaultPolicyDecisionOutput(true, List.of("name"), List.of("internal_owner"), List.of("email"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DefaultPolicyDecisionOutput.REQUEST_ATTRIBUTE, published);

        assertThat(resolve(request)).isSameAs(published);
    }

    /**
     * A request only reaches a handler if the PEP allowed it or never gated it, so "no
     * whole-request decision" resolves to allow with nothing filtered rather than to null.
     */
    @Test
    void noPublishedDecision_resolvesToAllowWithNothingFiltered() throws Exception {
        assertThat(resolve(new MockHttpServletRequest())).isEqualTo(DefaultPolicyDecisionOutput.ALLOW);
    }

    @Test
    void attributeOfAnotherType_isIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DefaultPolicyDecisionOutput.REQUEST_ATTRIBUTE, "ALLOW");

        assertThat(resolve(request)).isEqualTo(DefaultPolicyDecisionOutput.ALLOW);
    }
}
