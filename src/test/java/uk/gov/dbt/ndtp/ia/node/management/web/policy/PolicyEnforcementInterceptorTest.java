/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.DefaultPolicyDecisionOutput;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionClient;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInput;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInputFactory;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInputFixture;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyRequestBodyReader;

class PolicyEnforcementInterceptorTest {

    @Mock
    private PolicyDecisionClient policyDecisionClient;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private HandlerMethod handlerMethod;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Authentication authentication;

    @Mock
    private PolicyInputFactory policyInputFactory;

    @Mock
    private PolicyRequestBodyReader policyRequestBodyReader;

    private PolicyEnforcementInterceptor interceptor;
    private AutoCloseable closeable;
    private Logger logger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        interceptor = new PolicyEnforcementInterceptor(
                policyDecisionClient, policyInputFactory, policyRequestBodyReader, new ObjectMapper());
        // The factory is unit-tested separately; here it only has to mirror the request under test.
        lenient().when(policyInputFactory.create(any(), any())).thenAnswer(invocation -> {
            HttpServletRequest intercepted = invocation.getArgument(0);
            return PolicyInputFixture.of(
                    "client-1", "configuration", "producer", intercepted.getRequestURI(), intercepted.getMethod());
        });
        SecurityContextHolder.setContext(securityContext);

        logger = (Logger) LoggerFactory.getLogger(PolicyEnforcementInterceptor.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.detachAppender(logAppender);
        SecurityContextHolder.clearContext();
        closeable.close();
    }

    private void setupAuthentication(String clientId) {
        EnhancedPrincipal principal = new EnhancedPrincipal("subject", clientId, "test-organisation");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(principal);
    }

    private StringWriter setupResponseWriter() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        when(response.getWriter()).thenReturn(pw);
        return sw;
    }

    private enum MissingClientIdScenario {
        NO_AUTHENTICATION,
        NON_ENHANCED_PRINCIPAL,
        EMPTY_CLIENT_ID
    }

    @ParameterizedTest
    @EnumSource(MissingClientIdScenario.class)
    void missingClientId_returns403WithoutCallingPdp(MissingClientIdScenario scenario) throws Exception {
        switch (scenario) {
            case NO_AUTHENTICATION -> when(securityContext.getAuthentication()).thenReturn(null);
            case NON_ENHANCED_PRINCIPAL -> {
                when(securityContext.getAuthentication()).thenReturn(authentication);
                when(authentication.getPrincipal()).thenReturn("plain-string-principal");
            }
            case EMPTY_CLIENT_ID -> setupAuthentication("");
        }
        when(request.getRequestURI()).thenReturn("/api/v1/configuration/consumer");
        setupResponseWriter();

        assertThat(interceptor.preHandle(request, response, handlerMethod)).isFalse();
        verify(response).setStatus(403);
        verifyNoInteractions(policyDecisionClient);
    }

    @Test
    void pdpAllows_returnsTrue() throws Exception {
        setupAuthentication("client-1");
        when(request.getRequestURI()).thenReturn("/api/v1/configuration/producer");
        when(request.getMethod()).thenReturn("GET");
        when(policyDecisionClient.evaluate(any(PolicyInput.class))).thenReturn(DefaultPolicyDecisionOutput.ALLOW);

        assertThat(interceptor.preHandle(request, response, handlerMethod)).isTrue();
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void pdpDenies_returns403() throws Exception {
        setupAuthentication("client-1");
        when(request.getRequestURI()).thenReturn("/api/v1/configuration/producer");
        when(request.getMethod()).thenReturn("GET");
        when(policyDecisionClient.evaluate(any(PolicyInput.class))).thenReturn(DefaultPolicyDecisionOutput.DENY);
        StringWriter sw = setupResponseWriter();

        assertThat(interceptor.preHandle(request, response, handlerMethod)).isFalse();
        verify(response).setStatus(403);
        assertThat(sw.toString()).contains("403").contains("Access denied by policy");
    }

    @Test
    void pdpAllows_publishesTheDecisionForTheHandler() throws Exception {
        setupAuthentication("client-1");
        when(request.getRequestURI()).thenReturn("/api/v1/configuration/producer");
        when(request.getMethod()).thenReturn("GET");
        DefaultPolicyDecisionOutput decision =
                new DefaultPolicyDecisionOutput(true, List.of("name"), List.of("internal_owner"), List.of("email"));
        when(policyDecisionClient.evaluate(any(PolicyInput.class))).thenReturn(decision);

        assertThat(interceptor.preHandle(request, response, handlerMethod)).isTrue();

        // Published whole, not reduced to allow/deny: the handler acts on the attribute lists too.
        verify(request).setAttribute(DefaultPolicyDecisionOutput.REQUEST_ATTRIBUTE, decision);
    }

    @Test
    void pdpDenies_publishesNoDecision() throws Exception {
        setupAuthentication("client-1");
        when(request.getRequestURI()).thenReturn("/api/v1/configuration/producer");
        when(request.getMethod()).thenReturn("GET");
        when(policyDecisionClient.evaluate(any(PolicyInput.class))).thenReturn(DefaultPolicyDecisionOutput.DENY);
        setupResponseWriter();

        assertThat(interceptor.preHandle(request, response, handlerMethod)).isFalse();

        verify(request, never()).setAttribute(eq(DefaultPolicyDecisionOutput.REQUEST_ATTRIBUTE), any());
    }

    @Test
    void policyInput_isBuiltByTheFactoryAndForwardedToThePdp() throws Exception {
        setupAuthentication("client-1");
        PolicyInput built = PolicyInputFixture.of("client-1", "configuration", "consumer");
        doReturn(built).when(policyInputFactory).create(any(), any());
        when(policyDecisionClient.evaluate(any(PolicyInput.class))).thenReturn(DefaultPolicyDecisionOutput.ALLOW);

        interceptor.preHandle(request, response, handlerMethod);

        verify(policyDecisionClient).evaluate(built);
    }

    @Test
    void bufferedBody_reachesThePolicyInput() throws Exception {
        setupAuthentication("client-1");
        Map<String, Object> body = Map.of("text", "this is sample text");
        when(policyRequestBodyReader.read(request)).thenReturn(body);
        when(policyDecisionClient.evaluate(any(PolicyInput.class))).thenReturn(DefaultPolicyDecisionOutput.ALLOW);

        interceptor.preHandle(request, response, handlerMethod);

        verify(policyInputFactory).create(request, body);
    }

    @Test
    void unbufferedRequest_buildsTheInputWithNoBodyRatherThanFailing() throws Exception {
        setupAuthentication("client-1");
        when(policyRequestBodyReader.read(request)).thenReturn(null);
        when(policyDecisionClient.evaluate(any(PolicyInput.class))).thenReturn(DefaultPolicyDecisionOutput.ALLOW);

        interceptor.preHandle(request, response, handlerMethod);

        verify(policyInputFactory).create(request, null);
    }

    @Test
    void pdpAllows_logsAllowDecisionAtInfo() throws Exception {
        setupAuthentication("client-1");
        when(request.getRequestURI()).thenReturn("/api/v1/configuration/producer");
        when(request.getMethod()).thenReturn("GET");
        when(policyDecisionClient.evaluate(any(PolicyInput.class))).thenReturn(DefaultPolicyDecisionOutput.ALLOW);

        interceptor.preHandle(request, response, handlerMethod);

        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .contains("ALLOW")
                    .contains("client-1")
                    .contains("/api/v1/configuration/producer")
                    .contains("GET")
                    .contains("correlationId=");
        });
    }

    @Test
    void pdpDenies_logsDenyDecisionAtWarnWithCorrelationIdMatchingResponseBody() throws Exception {
        setupAuthentication("client-1");
        when(request.getRequestURI()).thenReturn("/api/v1/configuration/producer");
        when(request.getMethod()).thenReturn("GET");
        when(policyDecisionClient.evaluate(any(PolicyInput.class))).thenReturn(DefaultPolicyDecisionOutput.DENY);
        StringWriter sw = setupResponseWriter();

        interceptor.preHandle(request, response, handlerMethod);

        ILoggingEvent denyEvent = logAppender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("DENY"))
                .findFirst()
                .orElseThrow();
        assertThat(denyEvent.getLevel()).isEqualTo(Level.WARN);
        assertThat(denyEvent.getFormattedMessage()).contains("client-1").contains("/api/v1/configuration/producer");

        String message = denyEvent.getFormattedMessage();
        String correlationId = message.substring(message.indexOf("correlationId=") + "correlationId=".length());
        assertThat(sw.toString()).contains(correlationId);
    }
}
