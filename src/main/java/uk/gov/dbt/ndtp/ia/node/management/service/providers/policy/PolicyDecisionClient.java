/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <p>Every evaluation answers with a {@link PolicyDecision}: the verdict plus the
 * attribute lists the policy returned. Callers that only need allow/deny read
 * {@link PolicyDecision#allow()}; callers that shape a response from the decision
 * read the lists. A skipped or failed evaluation is still a decision document - a permissive one
 * when OPA is switched off, a refusal when the PDP cannot answer.
 *
 * <p>The rule-specific {@code details} are converted to the {@link PolicyDecisionDetails} subclass the
 * caller declares, with the
 * application's {@link ObjectMapper} so they bind the way any other JSON in the service does.
 * Details the declared type cannot hold are a DENY: a caller that acts on typed details must not
 * be handed a permission whose conditions it cannot read.
 */
@Component
@Slf4j
public class PolicyDecisionClient {

    private final RestClient opaRestClient;
    private final PolicyInputLogger policyInputLogger;
    private final PolicyOutputLogger policyOutputLogger;
    private final ObjectMapper objectMapper;
    private final String decisionPath;
    private final boolean enabled;

    public PolicyDecisionClient(
            RestClient opaRestClient,
            OpaProperties opaProperties,
            PolicyInputLogger policyInputLogger,
            PolicyOutputLogger policyOutputLogger,
            ObjectMapper objectMapper) {
        this.opaRestClient = opaRestClient;
        this.objectMapper = objectMapper;
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
     * Asks the PDP to decide on one input, leaving the details in their generic form.
     *
     * @param input the decision document to evaluate
     * @return the policy's decision; never null, and DENY whenever the PDP cannot be reached,
     *     answers with an error, or returns something that cannot be read as a decision
     */
    public PolicyDecision<PolicyDecisionDetails> evaluate(PolicyInput input) {
        return evaluate(input, PolicyDecisionDetails.class);
    }

    /**
     * Asks the PDP to decide on one input, reading the rule's details into {@code detailsType}.
     *
     * @param input the decision document to evaluate
     * @param detailsType the {@link PolicyDecisionDetails} subclass the details are read into;
     *     {@code PolicyDecisionDetails} itself keeps them in the generic form
     * @return the policy's decision; never null, and DENY whenever the PDP cannot be reached,
     *     answers with an error, returns something that cannot be read as a decision, or returns
     *     details that cannot be read as {@code detailsType}. A decision no rule answered carries
     *     empty details of {@code detailsType}.
     */
    public <D extends PolicyDecisionDetails> PolicyDecision<D> evaluate(PolicyInput input, Class<D> detailsType) {
        if (!enabled) {
            log.debug("OPA disabled; returning ALLOW without evaluating policy for action {}", input.action());
            return PolicyDecision.of(true, detailsType);
        }
        policyInputLogger.logInput(input);
        Object resourceKind = input.resource() == null ? null : input.resource().kind();
        PolicyDecision<PolicyDecisionDetails> output;
        try {
            PolicyDecisionResponse response = opaRestClient
                    .post()
                    .uri(decisionPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PolicyDecisionRequest(input))
                    .retrieve()
                    .body(PolicyDecisionResponse.class);

            output = response == null || response.result() == null ? PolicyDecision.DENY : response.result();
            policyOutputLogger.logOutput(input, output);
        } catch (Exception e) {
            log.warn("PDP invocation failed for resource {} action {}: {}", resourceKind, input.action(), e.toString());
            return PolicyDecision.of(false, detailsType);
        }
        return withTypedDetails(output, detailsType, resourceKind, input.action());
    }

    private <D extends PolicyDecisionDetails> PolicyDecision<D> withTypedDetails(
            PolicyDecision<PolicyDecisionDetails> output, Class<D> detailsType, Object resourceKind, String action) {
        if (detailsType.equals(output.details().getClass())) {
            return output.withDetails(detailsType.cast(output.details()));
        }
        try {
            return output.withDetails(objectMapper.convertValue(output.details(), detailsType));
        } catch (IllegalArgumentException e) {
            PolicyProvenance policy = output.policy();
            log.warn(
                    "PDP details for resource {} action {} from policy {} ({}) cannot be read as {}; denying: {}",
                    resourceKind,
                    action,
                    policy.id(),
                    policy.version(),
                    detailsType.getName(),
                    e.getMessage());
            return PolicyDecision.deny(PolicyDecision.REASON_DETAILS_UNREADABLE, policy, detailsType);
        }
    }
}
