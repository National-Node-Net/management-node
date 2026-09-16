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
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.DefaultPolicyDecisionOutput;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionClient;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInput;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInputFactory;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyRequestBodyReader;

/**
 * Policy Enforcement Point: intercepts requests to policy-aware APIs, enriches them
 * with identity and resource attributes, and enforces the PDP (OPA) allow/deny decision.
 * Runs after authentication has already populated the {@link SecurityContextHolder}.
 *
 * <p>An allowed request carries its decision onward: the full
 * {@link DefaultPolicyDecisionOutput} is published as a request attribute, so a handler can be
 * given the attribute lists the policy returned without asking for a second decision. See
 * {@link PolicyDecisionOutputArgumentResolver}.
 */
@Component
@Slf4j
public class PolicyEnforcementInterceptor implements HandlerInterceptor {

    private final PolicyDecisionClient policyDecisionClient;
    private final PolicyInputFactory policyInputFactory;
    private final PolicyRequestBodyReader policyRequestBodyReader;
    private final ObjectMapper objectMapper;

    public PolicyEnforcementInterceptor(
            PolicyDecisionClient policyDecisionClient,
            PolicyInputFactory policyInputFactory,
            PolicyRequestBodyReader policyRequestBodyReader,
            ObjectMapper objectMapper) {
        this.policyDecisionClient = policyDecisionClient;
        this.policyInputFactory = policyInputFactory;
        this.policyRequestBodyReader = policyRequestBodyReader;
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

        // preHandle runs before the handler binds the body, and a servlet body can only be read
        // once. PolicyBodyCachingFilter has already buffered it on these paths, so reading it here
        // is safe and the handler still gets a stream to bind; an unbuffered request yields null.
        PolicyInput input = policyInputFactory.create(request, policyRequestBodyReader.read(request));
        String path = input.request().path();
        String action = input.action();
        String method = input.request().method();

        DefaultPolicyDecisionOutput decision = policyDecisionClient.evaluate(input);

        if (decision.allow()) {
            // Published before the handler runs, so the handler is given the decision that let it
            // run rather than a fresh one: a second evaluation could answer differently.
            request.setAttribute(DefaultPolicyDecisionOutput.REQUEST_ATTRIBUTE, decision);
            log.info(
                    "Policy decision ALLOW clientId={} path={} action={} method={} correlationId={}",
                    clientId,
                    path,
                    action,
                    method,
                    correlationId);
            return true;
        }

        log.warn(
                "Policy decision DENY clientId={} path={} action={} method={} correlationId={}",
                clientId,
                path,
                action,
                method,
                correlationId);
        RequestRejectionSupport.writeError(
                response, objectMapper, HttpServletResponse.SC_FORBIDDEN, "Access denied by policy", correlationId);
        return false;
    }
}
