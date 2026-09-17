/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.policy;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;
import uk.gov.dbt.ndtp.ia.node.management.exception.AccessRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionClient;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInput;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyInputFactory;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyRequestBodyReader;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyTarget;
import uk.gov.dbt.ndtp.ia.node.management.web.RequestContextSupport;
import uk.gov.dbt.ndtp.ia.node.management.web.RequestRejectionSupport;

/**
 * Policy Enforcement Point: asks the PDP for a decision on every controller method carrying
 * {@link Policy}, and lets the call proceed only when it allows.
 *
 * <p>A method interceptor rather than a {@code HandlerInterceptor}. Handler interceptors run before
 * the handler method is invoked, which is before {@code @PreAuthorize} is evaluated - so a caller
 * without the endpoint's role would still have been sent to the PDP, and would have learnt the
 * policy's verdict before authorization refused it. Advising the method, ordered after method
 * security (see {@code RequestEnforcementConfig}), means the PDP is consulted only for callers
 * authorization has already accepted.
 *
 * <p>Running inside the handler call has one consequence for the decision parameter: Spring has
 * already resolved the handler's arguments, so an allowed decision is written into the
 * {@code Optional<PolicyDecision<...>>} argument here, its details already read into the
 * {@code @Policy(details = ...)} type,, as well as published as a request
 * attribute for anything downstream.
 */
@Component
@Slf4j
public class PolicyEnforcementInterceptor implements MethodInterceptor {

    private final PolicyDecisionClient policyDecisionClient;
    private final PolicyInputFactory policyInputFactory;
    private final PolicyRequestBodyReader policyRequestBodyReader;
    private final boolean enabled;

    public PolicyEnforcementInterceptor(
            PolicyDecisionClient policyDecisionClient,
            PolicyInputFactory policyInputFactory,
            PolicyRequestBodyReader policyRequestBodyReader,
            OpaProperties opaProperties) {
        this.policyDecisionClient = policyDecisionClient;
        this.policyInputFactory = policyInputFactory;
        this.policyRequestBodyReader = policyRequestBodyReader;
        this.enabled = opaProperties.enabled();
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Policy policy = AnnotatedElementUtils.findMergedAnnotation(method, Policy.class);
        if (policy == null || !enabled) {
            return invocation.proceed();
        }

        PolicyTarget<?> target = new PolicyTarget<>(policy.resource(), policy.action(), policy.details());
        String correlationId = UUID.randomUUID().toString();
        // A @Policy method called outside a request has no caller to judge; refusing keeps the
        // annotation a guarantee rather than something a direct call can step around.
        HttpServletRequest request = RequestContextSupport.currentRequest()
                .orElseThrow(() -> new AccessRejectedException("Access denied by policy", correlationId));
        String clientId = RequestRejectionSupport.extractClientId();

        // PolicyBodyCachingFilter buffered the body for @Policy handlers before the handler bound it,
        // so the PDP sees the body as sent; an unbuffered request yields null.
        PolicyInput input = policyInputFactory.create(request, policyRequestBodyReader.read(request), target);
        PolicyDecision<?> decision = policyDecisionClient.evaluate(input, target.detailsType());

        if (!decision.allow()) {
            log.warn(
                    "Policy decision DENY clientId={} resource={} action={} path={} method={} reasons={} policy={} "
                            + "correlationId={}",
                    clientId,
                    target.resource(),
                    target.action(),
                    request.getRequestURI(),
                    request.getMethod(),
                    decision.reasons(),
                    decision.policy(),
                    correlationId);
            throw new AccessRejectedException("Access denied by policy", decision.callerReasons(), correlationId);
        }

        log.info(
                "Policy decision ALLOW clientId={} resource={} action={} path={} method={} policy={} correlationId={}",
                clientId,
                target.resource(),
                target.action(),
                request.getRequestURI(),
                request.getMethod(),
                decision.policy(),
                correlationId);
        request.setAttribute(PolicyDecision.REQUEST_ATTRIBUTE, decision);
        handDecisionToHandler(invocation, decision);
        return invocation.proceed();
    }

    /**
     * Writes the decision into every {@code Optional<PolicyDecision<...>>} parameter. The
     * invocation's argument array is the one the handler receives, and is documented as writable
     * for exactly this purpose.
     */
    private static void handDecisionToHandler(MethodInvocation invocation, PolicyDecision<?> decision) {
        Object[] arguments = invocation.getArguments();
        Method method = invocation.getMethod();
        for (int index = 0; index < arguments.length; index++) {
            if (PolicyDecisionArgumentResolver.isDecisionParameter(new MethodParameter(method, index))) {
                arguments[index] = Optional.of(decision);
            }
        }
    }
}
