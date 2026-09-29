/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionDetails;

/**
 * Covers how a controller is given the PDP's decision: present only when policy enforcement is on
 * and a decision was published for the request, empty in every other case.
 */
class PolicyDecisionArgumentResolverTest {

    private static final PolicyDecision<PolicyDecisionDetails> PUBLISHED =
            PolicyDecision.of(true, PolicyDecisionDetails.class).withDetails(new PolicyDecisionDetails(Map.of("a", 1)));

    /** A details subclass a handler might declare. */
    static class ViewDetails extends PolicyDecisionDetails {}

    @SuppressWarnings("unused")
    void handler(
            Optional<PolicyDecision<PolicyDecisionDetails>> decision,
            PolicyDecision<PolicyDecisionDetails> unwrapped,
            Optional<String> otherOptional,
            Optional<PolicyDecision<ViewDetails>> typedDecision,
            Optional<PolicyDecision<?>> wildcardDecision) {}

    private static MethodParameter parameter(int index) throws NoSuchMethodException {
        Method method = PolicyDecisionArgumentResolverTest.class.getDeclaredMethod(
                "handler", Optional.class, PolicyDecision.class, Optional.class, Optional.class, Optional.class);
        return new MethodParameter(method, index);
    }

    private static PolicyDecisionArgumentResolver resolver(boolean opaEnabled) {
        return new PolicyDecisionArgumentResolver(new OpaProperties(
                opaEnabled,
                "http://localhost:8181",
                "/v1/data/dispatch/decision",
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                List.of("content-type"),
                false,
                false));
    }

    private static Optional<?> resolve(boolean opaEnabled, MockHttpServletRequest request) throws Exception {
        return resolve(opaEnabled, request, 0);
    }

    private static Optional<?> resolve(boolean opaEnabled, MockHttpServletRequest request, int parameterIndex)
            throws Exception {
        return resolver(opaEnabled)
                .resolveArgument(parameter(parameterIndex), null, new ServletWebRequest(request), null);
    }

    private static MockHttpServletRequest requestWithPublishedDecision() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(PolicyDecision.REQUEST_ATTRIBUTE, PUBLISHED);
        return request;
    }

    @Test
    void supportsOnlyAnOptionalDecisionParameter() throws Exception {
        PolicyDecisionArgumentResolver resolver = resolver(true);

        assertThat(resolver.supportsParameter(parameter(0))).isTrue();
        // One way to ask for the decision, so every handler has to deal with its absence.
        assertThat(resolver.supportsParameter(parameter(1))).isFalse();
        assertThat(resolver.supportsParameter(parameter(2))).isFalse();
        assertThat(resolver.supportsParameter(parameter(3))).isTrue();
        assertThat(resolver.supportsParameter(parameter(4))).isTrue();
    }

    @Test
    void declaredDetailsType_isTheDecisionsTypeArgument() throws Exception {
        assertThat(PolicyDecisionArgumentResolver.declaredDetailsType(parameter(0)))
                .isEqualTo(PolicyDecisionDetails.class);
        assertThat(PolicyDecisionArgumentResolver.declaredDetailsType(parameter(3)))
                .isEqualTo(ViewDetails.class);
        assertThat(PolicyDecisionArgumentResolver.declaredDetailsType(parameter(4)))
                .isEqualTo(PolicyDecisionDetails.class);
    }

    @Test
    void opaEnabled_typedDecision_isHandedToAParameterDeclaringItsType() throws Exception {
        PolicyDecision<ViewDetails> published = PolicyDecision.of(true, ViewDetails.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(PolicyDecision.REQUEST_ATTRIBUTE, published);

        assertThat(resolve(true, request, 3)).get().isSameAs(published);
        // A parameter declaring the generic details type can hold any subclass.
        assertThat(resolve(true, request, 0)).get().isSameAs(published);
    }

    /** A decision whose details the parameter cannot hold is never handed over as the wrong type. */
    @Test
    void opaEnabled_decisionOfAnotherDetailsType_isEmpty() throws Exception {
        assertThat(resolve(true, requestWithPublishedDecision(), 3)).isEmpty();
    }

    @Test
    void opaEnabled_publishedDecision_isHandedToTheController() throws Exception {
        assertThat(resolve(true, requestWithPublishedDecision())).get().isSameAs(PUBLISHED);
    }

    /** Enabled, but no decision was taken for this request - e.g. an allowed request whose decision was not published. */
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
        request.setAttribute(PolicyDecision.REQUEST_ATTRIBUTE, "ALLOW");

        assertThat(resolve(true, request)).isEmpty();
    }
}
