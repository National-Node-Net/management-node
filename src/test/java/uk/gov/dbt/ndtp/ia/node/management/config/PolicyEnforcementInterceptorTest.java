/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

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
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionClient;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInput;

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

    private PolicyEnforcementInterceptor interceptor;
    private AutoCloseable closeable;
    private Logger logger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        interceptor = new PolicyEnforcementInterceptor(policyDecisionClient, new ObjectMapper());
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
        when(policyDecisionClient.evaluate(any(PolicyInput.class))).thenReturn(PolicyDecision.ALLOW);

        assertThat(interceptor.preHandle(request, response, handlerMethod)).isTrue();
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void pdpDenies_returns403() throws Exception {
        setupAuthentication("client-1");
        when(request.getRequestURI()).thenReturn("/api/v1/configuration/producer");
        when(request.getMethod()).thenReturn("GET");
        when(policyDecisionClient.evaluate(any(PolicyInput.class))).thenReturn(PolicyDecision.DENY);
        StringWriter sw = setupResponseWriter();

        assertThat(interceptor.preHandle(request, response, handlerMethod)).isFalse();
        verify(response).setStatus(403);
        assertThat(sw.toString()).contains("403").contains("Access denied by policy");
    }

    @Test
    void policyInput_includesClientResourceAndAction() throws Exception {
        setupAuthentication("client-1");
        when(request.getRequestURI()).thenReturn("/api/v1/configuration/consumer");
        when(request.getMethod()).thenReturn("GET");
        when(policyDecisionClient.evaluate(any(PolicyInput.class))).thenReturn(PolicyDecision.ALLOW);

        interceptor.preHandle(request, response, handlerMethod);

        verify(policyDecisionClient)
                .evaluate(new PolicyInput(
                        "client-1", "test-organisation", null, "/api/v1/configuration/consumer", "GET"));
    }

    @Test
    void policyInput_includesBothTokenOrganisationAndCertificateOrganisationId() throws Exception {
        setupAuthentication("client-1");
        when(request.getRequestURI()).thenReturn("/api/v1/configuration/consumer");
        when(request.getMethod()).thenReturn("GET");
        when(request.getAttribute("ndtp.organisationId")).thenReturn(42L);
        when(policyDecisionClient.evaluate(any(PolicyInput.class))).thenReturn(PolicyDecision.ALLOW);

        interceptor.preHandle(request, response, handlerMethod);

        verify(policyDecisionClient)
                .evaluate(new PolicyInput(
                        "client-1", "test-organisation", "42", "/api/v1/configuration/consumer", "GET"));
    }

    @Test
    void pdpAllows_logsAllowDecisionAtInfo() throws Exception {
        setupAuthentication("client-1");
        when(request.getRequestURI()).thenReturn("/api/v1/configuration/producer");
        when(request.getMethod()).thenReturn("GET");
        when(policyDecisionClient.evaluate(any(PolicyInput.class))).thenReturn(PolicyDecision.ALLOW);

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
        when(policyDecisionClient.evaluate(any(PolicyInput.class))).thenReturn(PolicyDecision.DENY);
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
