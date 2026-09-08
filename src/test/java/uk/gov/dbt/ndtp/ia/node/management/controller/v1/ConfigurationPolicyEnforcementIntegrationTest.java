/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.controller.v1;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.dbt.ndtp.ia.node.management.config.PolicyEnforcementInterceptor;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ConsumerConfigDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.ProducerConfigDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.configuration.ConfigurationProvider;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionClient;

/**
 * Integration test wiring {@link PolicyEnforcementInterceptor} in front of
 * {@link ConfigurationController} via MockMvc, verifying the PEP enforces the PDP
 * decision for both permitted and denied requests (AC7).
 */
@ExtendWith(MockitoExtension.class)
class ConfigurationPolicyEnforcementIntegrationTest {

    @Mock
    private ConfigurationProvider configurationProvider;

    @Mock
    private PolicyDecisionClient policyDecisionClient;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ConfigurationController controller = new ConfigurationController(configurationProvider);
        PolicyEnforcementInterceptor interceptor =
                new PolicyEnforcementInterceptor(policyDecisionClient, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(interceptor)
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String clientId) {
        EnhancedPrincipal principal = new EnhancedPrincipal("subject", clientId);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        SecurityContext context = mock(SecurityContext.class);
        when(context.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(context);
    }

    @Test
    void allowedRequest_reachesControllerAndReturnsConfig() throws Exception {
        authenticateAs("client-1");
        when(policyDecisionClient.evaluate(any())).thenReturn(PolicyDecision.ALLOW);
        when(configurationProvider.getProducerConfigByClientId(any(), any()))
                .thenReturn(ProducerConfigDTO.builder()
                        .clientId("client-1")
                        .producers(Collections.emptyList())
                        .build());

        mockMvc.perform(get("/api/v1/configuration/producer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("client-1"));

        verify(configurationProvider).getProducerConfigByClientId(any(), any());
    }

    @Test
    void deniedRequest_rejectedBeforeReachingController() throws Exception {
        authenticateAs("client-1");
        when(policyDecisionClient.evaluate(any())).thenReturn(PolicyDecision.DENY);

        mockMvc.perform(get("/api/v1/configuration/consumer")).andExpect(status().isForbidden());

        verifyNoInteractions(configurationProvider);
    }

    @Test
    void deniedRequest_onProducerEndpoint_rejectedBeforeReachingController() throws Exception {
        authenticateAs("client-1");
        when(policyDecisionClient.evaluate(any())).thenReturn(PolicyDecision.DENY);

        mockMvc.perform(get("/api/v1/configuration/producer")).andExpect(status().isForbidden());

        verifyNoInteractions(configurationProvider);
    }

    @Test
    void allowedRequest_onConsumerEndpoint_reachesControllerAndReturnsConfig() throws Exception {
        authenticateAs("client-1");
        when(policyDecisionClient.evaluate(any())).thenReturn(PolicyDecision.ALLOW);
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
    void unauthenticatedRequest_rejectedWithoutInvokingPdpOrController() throws Exception {
        mockMvc.perform(get("/api/v1/configuration/producer")).andExpect(status().isForbidden());

        verifyNoInteractions(policyDecisionClient);
        verifyNoInteractions(configurationProvider);
    }
}
