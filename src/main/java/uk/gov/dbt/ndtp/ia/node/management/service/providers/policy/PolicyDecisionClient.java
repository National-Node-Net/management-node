/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;

/**
 * Invokes the PDP (OPA) with a policy decision request and interprets the response.
 * Any failure to reach or parse a response from the PDP is treated as DENY, so a PDP
 * outage fails closed rather than silently disabling policy enforcement.
 *
 * <p>Every evaluation answers with a {@link DefaultPolicyDecisionOutput}: the verdict plus the
 * attribute lists the policy returned. Callers that only need allow/deny read
 * {@link DefaultPolicyDecisionOutput#allow()}; callers that shape a response from the decision
 * read the lists. A skipped or failed evaluation is still a decision document - a permissive one
 * when OPA is switched off, {@link DefaultPolicyDecisionOutput#DENY} when the PDP cannot answer.
 */
@Component
@Slf4j
public class PolicyDecisionClient {

    private final RestClient opaRestClient;
    private final PolicyInputLogger policyInputLogger;
    private final PolicyOutputLogger policyOutputLogger;
    private final String decisionPath;
    private final boolean enabled;

    public PolicyDecisionClient(
            RestClient opaRestClient,
            OpaProperties opaProperties,
            PolicyInputLogger policyInputLogger,
            PolicyOutputLogger policyOutputLogger) {
        this.opaRestClient = opaRestClient;
        this.policyInputLogger = policyInputLogger;
        this.policyOutputLogger = policyOutputLogger;
        this.decisionPath = opaProperties.decisionPath();
        this.enabled = opaProperties.enabled();
    }

    /**
     * Warns once at startup when policy enforcement is off, rather than on every decision: product
     * discovery alone asks for one decision per candidate, so a per-call warning would bury the
     * very message it is meant to make visible.
     */
    @PostConstruct
    void warnWhenDisabled() {
        if (!enabled) {
            log.warn("OPA is switched off in configuration (application.opa.enabled=false). Policy will "
                    + "NOT be evaluated: every decision returns ALLOW and the Policy Enforcement "
                    + "Point is not registered. Set application.opa.enabled=true to enforce policy.");
        }
    }

    /**
     * Asks the PDP to decide on one input.
     *
     * @param input the decision document to evaluate
     * @return the policy's decision; never null, and DENY whenever the PDP cannot be reached,
     *     answers with an error, or returns something that cannot be read as a decision
     */
    public DefaultPolicyDecisionOutput evaluate(PolicyInput input) {
        if (!enabled) {
            log.debug("OPA disabled; returning ALLOW without evaluating policy for action {}", input.action());
            return DefaultPolicyDecisionOutput.ALLOW;
        }
        policyInputLogger.logInput(input);
        Object resourceKind = input.resource() == null ? null : input.resource().kind();
        try {
            PolicyDecisionResponse response = opaRestClient
                    .post()
                    .uri(decisionPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PolicyDecisionRequest(input))
                    .retrieve()
                    .body(PolicyDecisionResponse.class);

            DefaultPolicyDecisionOutput output = response == null || response.result() == null
                    ? DefaultPolicyDecisionOutput.DENY
                    : response.result();
            policyOutputLogger.logOutput(input, output);
            return output;
        } catch (Exception e) {
            log.warn("PDP invocation failed for resource {} action {}: {}", resourceKind, input.action(), e.toString());
            return DefaultPolicyDecisionOutput.DENY;
        }
    }
}
