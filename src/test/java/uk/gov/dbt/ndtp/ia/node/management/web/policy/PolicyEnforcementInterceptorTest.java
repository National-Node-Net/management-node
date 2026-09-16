/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;
import uk.gov.dbt.ndtp.ia.node.management.exception.AccessRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionClient;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInput;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInputFactory;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInputFixture;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyProvenance;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyRequestBodyReader;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyTarget;

/**
 * Covers the Policy Enforcement Point as method advice: which calls it judges, what it asks the PDP,
 * how an allowed decision reaches the handler, and that a denial stops the call before the handler
 * runs while keeping the policy's reasons out of the response.
 */
@ExtendWith(MockitoExtension.class)
class PolicyEnforcementInterceptorTest {

    /** A details subclass, so the declared details type is distinct from the generic default. */
    static class SubscriptionDetails extends PolicyDecisionDetails {}

    static class SampleController {

        @Policy(resource = "product", action = "subscribe", details = SubscriptionDetails.class)
        public void subscribe(String body, Optional<PolicyDecision<SubscriptionDetails>> decision) {}

        @Policy(resource = "configuration", action = "producer")
        public void producer() {}

        public void unannotated() {}
    }

    private static final PolicyInput INPUT = PolicyInputFixture.of("client-1", "product", "subscribe");
    private static final String HANDLED = "handled";

    @Mock
    private PolicyDecisionClient policyDecisionClient;

    @Mock
    private PolicyInputFactory policyInputFactory;

    @Mock
    private PolicyRequestBodyReader policyRequestBodyReader;

    @Mock
    private MethodInvocation invocation;

    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/product/subscribe");

    private Logger logger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        lenient().when(policyInputFactory.create(any(), any(), any())).thenReturn(INPUT);
        EnhancedPrincipal principal = new EnhancedPrincipal("subject", "client-1", "test-organisation");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal, null));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        logger = (Logger) LoggerFactory.getLogger(PolicyEnforcementInterceptor.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private PolicyEnforcementInterceptor interceptor(boolean opaEnabled) {
        OpaProperties properties = new OpaProperties(
                opaEnabled,
                "http://localhost:8181",
                "/v1/data/dispatch/decision",
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                List.of("content-type"),
                false,
                false);
        return new PolicyEnforcementInterceptor(
                policyDecisionClient, policyInputFactory, policyRequestBodyReader, properties);
    }

    private Object[] invoking(String methodName, Object... arguments) throws Throwable {
        Method method = java.util.Arrays.stream(SampleController.class.getMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        lenient().when(invocation.getMethod()).thenReturn(method);
        lenient().when(invocation.getArguments()).thenReturn(arguments);
        lenient().when(invocation.proceed()).thenReturn(HANDLED);
        return arguments;
    }

    // ---------------------------------------------------------------------------------------
    // Which calls are judged
    // ---------------------------------------------------------------------------------------

    @Test
    void unannotatedMethod_proceedsWithoutBodyReadOrPdpCall() throws Throwable {
        invoking("unannotated");

        assertThat(interceptor(true).invoke(invocation)).isEqualTo(HANDLED);
        verifyNoInteractions(policyDecisionClient, policyInputFactory, policyRequestBodyReader);
    }

    @Test
    void opaSwitchedOff_proceedsWithoutPdpCall() throws Throwable {
        invoking("producer");

        assertThat(interceptor(false).invoke(invocation)).isEqualTo(HANDLED);
        verifyNoInteractions(policyDecisionClient, policyInputFactory, policyRequestBodyReader);
    }

    /** A @Policy method called outside a request has no caller to judge, so it is refused. */
    @Test
    void annotatedMethodOutsideARequest_isRejected() throws Throwable {
        RequestContextHolder.resetRequestAttributes();
        invoking("producer");

        assertThatThrownBy(() -> interceptor(true).invoke(invocation)).isInstanceOf(AccessRejectedException.class);
        verify(invocation, never()).proceed();
        verifyNoInteractions(policyDecisionClient);
    }

    // ---------------------------------------------------------------------------------------
    // What the PDP is asked
    // ---------------------------------------------------------------------------------------

    @Test
    void annotatedMethod_buildsTheTargetFromTheAnnotationAndPassesItsDetailsType() throws Throwable {
        invoking("subscribe", "{}", Optional.empty());
        Map<String, Object> body = Map.of("productId", 42);
        when(policyRequestBodyReader.read(request)).thenReturn(body);
        when(policyDecisionClient.evaluate(INPUT, SubscriptionDetails.class))
                .thenReturn(PolicyDecision.of(true, SubscriptionDetails.class));

        assertThat(interceptor(true).invoke(invocation)).isEqualTo(HANDLED);

        verify(policyInputFactory)
                .create(request, body, new PolicyTarget<>("product", "subscribe", SubscriptionDetails.class));
        verify(policyDecisionClient).evaluate(INPUT, SubscriptionDetails.class);
    }

    @Test
    void annotatedMethodWithoutDetails_asksForGenericDetails() throws Throwable {
        invoking("producer");
        when(policyDecisionClient.evaluate(INPUT, PolicyDecisionDetails.class)).thenReturn(PolicyDecision.ALLOW);

        interceptor(true).invoke(invocation);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<PolicyTarget<?>> target = (ArgumentCaptor) ArgumentCaptor.forClass(PolicyTarget.class);
        verify(policyInputFactory).create(eq(request), eq(null), target.capture());
        assertThat(target.getValue()).isEqualTo(PolicyTarget.of("configuration", "producer"));
    }

    @Test
    void unbufferedRequest_buildsTheInputWithNoBodyRatherThanFailing() throws Throwable {
        invoking("producer");
        when(policyRequestBodyReader.read(request)).thenReturn(null);
        when(policyDecisionClient.evaluate(any(), any())).thenReturn(PolicyDecision.ALLOW);

        interceptor(true).invoke(invocation);

        verify(policyInputFactory).create(eq(request), eq(null), any());
    }

    // ---------------------------------------------------------------------------------------
    // Allow
    // ---------------------------------------------------------------------------------------

    @Test
    void allow_handsTheDecisionToTheHandlerArgumentAndPublishesIt() throws Throwable {
        Object[] arguments = invoking("subscribe", "{}", Optional.empty());
        PolicyDecision<SubscriptionDetails> decision = PolicyDecision.of(
                        true, List.of("name"), List.of("internal_owner"), List.of("email"))
                .withDetails(new SubscriptionDetails());
        when(policyDecisionClient.evaluate(any(), eq(SubscriptionDetails.class)))
                .thenReturn(decision);

        assertThat(interceptor(true).invoke(invocation)).isEqualTo(HANDLED);

        // The argument was resolved empty before the decision existed; the handler must see it.
        assertThat(arguments[1]).isEqualTo(Optional.of(decision));
        assertThat(arguments[0]).isEqualTo("{}");
        assertThat(request.getAttribute(PolicyDecision.REQUEST_ATTRIBUTE)).isSameAs(decision);
        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .contains("ALLOW")
                    .contains("client-1")
                    .contains("resource=product")
                    .contains("action=subscribe")
                    .contains("correlationId=");
        });
    }

    // ---------------------------------------------------------------------------------------
    // Deny
    // ---------------------------------------------------------------------------------------

    @Test
    void deny_stopsTheCallWithAGenericRejectionAndLogsReasonsAndProvenance() throws Throwable {
        Object[] arguments = invoking("subscribe", "{}", Optional.empty());
        PolicyDecision<SubscriptionDetails> decision = new PolicyDecision<>(
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of("organisation.missing", "request.product_missing"),
                new PolicyProvenance("product.subscribe", "policies.product.subscribe/1.0.0", "exact"),
                new SubscriptionDetails());
        when(policyDecisionClient.evaluate(any(), eq(SubscriptionDetails.class)))
                .thenReturn(decision);

        AccessRejectedException rejection = (AccessRejectedException) org.assertj.core.api.Assertions.catchThrowable(
                () -> interceptor(true).invoke(invocation));

        assertThat(rejection).isNotNull();
        verify(invocation, never()).proceed();
        // The reasons describe the policy; they are for the log, never for the caller.
        assertThat(rejection.getMessage())
                .isEqualTo("Access denied by policy")
                .doesNotContain("organisation.missing")
                .doesNotContain("product.subscribe");
        assertThat(arguments[1]).isEqualTo(Optional.empty());
        assertThat(request.getAttribute(PolicyDecision.REQUEST_ATTRIBUTE)).isNull();

        ILoggingEvent denyEvent = logAppender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("DENY"))
                .findFirst()
                .orElseThrow();
        assertThat(denyEvent.getLevel()).isEqualTo(Level.WARN);
        assertThat(denyEvent.getFormattedMessage())
                .contains("organisation.missing")
                .contains("request.product_missing")
                .contains("policies.product.subscribe/1.0.0")
                .contains("exact")
                .contains("correlationId=" + rejection.getErrorId());
    }
}
