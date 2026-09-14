/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionClient;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInput;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyRequester;

/**
 * Policy Enforcement Point: intercepts requests to policy-aware APIs, enriches them
 * with identity and resource attributes, and enforces the PDP (OPA) allow/deny decision.
 * Runs after authentication has already populated the {@link SecurityContextHolder}.
 */
@Component
@Slf4j
public class PolicyEnforcementInterceptor implements HandlerInterceptor {

    private final PolicyDecisionClient policyDecisionClient;
    private final ObjectMapper objectMapper;

    public PolicyEnforcementInterceptor(PolicyDecisionClient policyDecisionClient, ObjectMapper objectMapper) {
        this.policyDecisionClient = policyDecisionClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String correlationId = UUID.randomUUID().toString();

        String clientId = RequestRejectionSupport.extractClientId();
        if (clientId == null) {
            log.warn("No client ID found for policy-aware request to {}", request.getRequestURI());
            RequestRejectionSupport.writeError(
                    response, objectMapper, HttpServletResponse.SC_FORBIDDEN, "Client ID required", correlationId);
            return false;
        }

        String resource = request.getRequestURI();
        String action = request.getMethod();
        PolicyRequester requester = new PolicyRequester(
                clientId,
                RequestRejectionSupport.extractOrganisation(),
                RequestRejectionSupport.getOrganisationId(request));
        PolicyInput input = PolicyInput.of(requester, resource, action);

        PolicyDecision decision = policyDecisionClient.evaluate(input);

        if (decision == PolicyDecision.ALLOW) {
            log.info(
                    "Policy decision ALLOW clientId={} resource={} action={} correlationId={}",
                    clientId,
                    resource,
                    action,
                    correlationId);
            return true;
        }

        log.warn(
                "Policy decision DENY clientId={} resource={} action={} correlationId={}",
                clientId,
                resource,
                action,
                correlationId);
        RequestRejectionSupport.writeError(
                response, objectMapper, HttpServletResponse.SC_FORBIDDEN, "Access denied by policy", correlationId);
        return false;
    }
}
