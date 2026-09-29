/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.controller.v1;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;
import uk.gov.dbt.ndtp.ia.node.management.exception.handlers.GlobalExceptionHandler;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OrganisationCertificateDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ConsumerConfigDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProducerConfigDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateType;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.CertificateValidationProvider;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.configuration.ConfigurationProvider;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionClient;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInputFactory;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInputFixture;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyProvenance;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyRequestBodyReader;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyTarget;
import uk.gov.dbt.ndtp.ia.node.management.support.EnforcedControllerProxy;
import uk.gov.dbt.ndtp.ia.node.management.web.certificate.CertificateValidationInterceptor;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.PolicyDecisionArgumentResolver;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.PolicyEnforcementInterceptor;

/**
 * Drives {@link ConfigurationController} through the same enforcement chain the application
 * builds - {@code @PreAuthorize}, then the organisation certificate check, then the policy decision -
 * and verifies each step only runs for a request every earlier step accepted. In particular a caller
 * without the endpoint's role is refused by authorization and never reaches the certificate check
 * or the PDP.
 */
@ExtendWith(MockitoExtension.class)
class ConfigurationPolicyEnforcementIntegrationTest {

    private static final String PRODUCER_ROLE = "ROLE_management-node:access_producer_configurations";
    private static final String CONSUMER_ROLE = "ROLE_management-node:access_consumer_configurations";

    @Mock
    private ConfigurationProvider configurationProvider;

    @Mock
    private CertificateValidationProvider certificateValidationProvider;

    @Mock
    private PolicyDecisionClient policyDecisionClient;

    @Mock
    private PolicyInputFactory policyInputFactory;

    @Mock
    private PolicyRequestBodyReader policyRequestBodyReader;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        OpaProperties opaProperties = new OpaProperties(
                true,
                "http://localhost:8181",
                "/v1/data/dispatch/decision",
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                List.of("content-type"),
                false,
                false);
        ConfigurationController controller = EnforcedControllerProxy.of(
                new ConfigurationController(configurationProvider),
                new CertificateValidationInterceptor(certificateValidationProvider),
                new PolicyEnforcementInterceptor(
                        policyDecisionClient, policyInputFactory, policyRequestBodyReader, opaProperties));
        lenient().when(policyInputFactory.create(any(), any(), any())).thenAnswer(invocation -> {
            PolicyTarget<?> target = invocation.getArgument(2);
            return PolicyInputFixture.of(
                    "client-1",
                    target.resource(),
                    target.action(),
                    ((HttpServletRequest) invocation.getArgument(0)).getRequestURI());
        });
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver(),
                        new PolicyDecisionArgumentResolver(opaProperties))
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String clientId, String... authorities) {
        EnhancedPrincipal principal = new EnhancedPrincipal("subject", clientId, "test-organisation");
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void activeCertificateFor(String clientId) {
        OrganisationCertificateDTO certificate = OrganisationCertificateDTO.builder()
                .id(1L)
                .organisationId(1L)
                .type(CertificateType.AUTOMATED)
                .expiresAt(Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)))
                .build();
        when(certificateValidationProvider.findByClientId(clientId)).thenReturn(Optional.of(certificate));
        when(certificateValidationProvider.isActive(certificate)).thenReturn(true);
    }

    // ---------------------------------------------------------------------------------------
    // Authorization comes first
    // ---------------------------------------------------------------------------------------

    @Test
    void callerWithoutTheRole_isRefusedByAuthorization_andNeverReachesCertificateOrPolicy() throws Exception {
        authenticateAs("client-1", "ROLE_management-node:some_other_role");

        mockMvc.perform(get("/api/v1/configuration/producer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied: insufficient permissions for this operation"));

        verifyNoInteractions(
                certificateValidationProvider, policyDecisionClient, policyInputFactory, configurationProvider);
    }

    // ---------------------------------------------------------------------------------------
    // Then the certificate
    // ---------------------------------------------------------------------------------------

    @Test
    void authorisedCallerWithoutACertificate_isRefused_andNeverReachesPolicy() throws Exception {
        authenticateAs("client-1", PRODUCER_ROLE);
        when(certificateValidationProvider.findByClientId("client-1")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/configuration/producer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("No organisation certificate found"))
                .andExpect(jsonPath("$.reasons").doesNotExist());

        verifyNoInteractions(policyDecisionClient, policyInputFactory, configurationProvider);
    }

    // ---------------------------------------------------------------------------------------
    // Then policy
    // ---------------------------------------------------------------------------------------

    @Test
    void allowedRequest_runsCertificateBeforePolicy_andReachesTheController() throws Exception {
        authenticateAs("client-1", PRODUCER_ROLE);
        activeCertificateFor("client-1");
        when(policyDecisionClient.evaluate(any(), any())).thenReturn(PolicyDecision.ALLOW);
        when(configurationProvider.getProducerConfigByClientId(any(), any()))
                .thenReturn(ProducerConfigDTO.builder()
                        .clientId("client-1")
                        .producers(Collections.emptyList())
                        .build());

        mockMvc.perform(get("/api/v1/configuration/producer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("client-1"));

        InOrder order = inOrder(certificateValidationProvider, policyDecisionClient, configurationProvider);
        order.verify(certificateValidationProvider).findByClientId("client-1");
        order.verify(policyDecisionClient).evaluate(any(), any());
        order.verify(configurationProvider).getProducerConfigByClientId(any(), any());
    }

    @Test
    void allowedRequest_onConsumerEndpoint_reachesControllerAndReturnsConfig() throws Exception {
        authenticateAs("client-1", CONSUMER_ROLE);
        activeCertificateFor("client-1");
        when(policyDecisionClient.evaluate(any(), any())).thenReturn(PolicyDecision.ALLOW);
        when(configurationProvider.getConsumerConfigByClientId(any(), any()))
                .thenReturn(ConsumerConfigDTO.builder()
                        .clientId("client-1")
                        .producers(Collections.emptyList())
                        .build());

        mockMvc.perform(get("/api/v1/configuration/consumer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("client-1"));

        verify(configurationProvider).getConsumerConfigByClientId(any(), any());
    }

    @Test
    void deniedByPolicy_onConsumerEndpoint_isRefusedBeforeReachingController() throws Exception {
        authenticateAs("client-1", CONSUMER_ROLE);
        activeCertificateFor("client-1");
        when(policyDecisionClient.evaluate(any(), any())).thenReturn(PolicyDecision.DENY);

        mockMvc.perform(get("/api/v1/configuration/consumer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied by policy"))
                .andExpect(jsonPath("$.reasons").doesNotExist())
                .andExpect(jsonPath("$.errorId").isNotEmpty());

        verifyNoInteractions(configurationProvider);
    }

    @Test
    void deniedByPolicy_onProducerEndpoint_isRefusedBeforeReachingController() throws Exception {
        authenticateAs("client-1", PRODUCER_ROLE);
        activeCertificateFor("client-1");
        when(policyDecisionClient.evaluate(any(), any())).thenReturn(PolicyDecision.DENY);

        mockMvc.perform(get("/api/v1/configuration/producer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied by policy"));

        verifyNoInteractions(configurationProvider);
    }

    @Test
    void deniedByPolicy_returnsTheCallerReasons_withoutReasonsAboutPolicyWiring() throws Exception {
        authenticateAs("client-1", PRODUCER_ROLE);
        activeCertificateFor("client-1");
        when(policyDecisionClient.evaluate(any(), any()))
                .thenReturn(new PolicyDecision<>(
                        false,
                        List.of("dispatch.resource_fallback", "organisation.clearance_insufficient"),
                        new PolicyProvenance(
                                "configuration.fallback", "policies.configuration.fallback/1.0.0", "resource_fallback"),
                        new PolicyDecisionDetails()));

        mockMvc.perform(get("/api/v1/configuration/producer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied by policy"))
                .andExpect(jsonPath("$.reasons.length()").value(1))
                .andExpect(jsonPath("$.reasons[0]").value("organisation.clearance_insufficient"))
                .andExpect(jsonPath("$.errorId").isNotEmpty());

        verifyNoInteractions(configurationProvider);
    }
}
