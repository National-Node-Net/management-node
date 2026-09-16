/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.policy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.DefaultPolicyDecisionOutput;

/**
 * Injects the PDP's decision into any controller method that declares a
 * {@link DefaultPolicyDecisionOutput} parameter, so a handler can pass the decision on to the
 * service layer instead of re-deriving it.
 *
 * <p>The value is the one {@link PolicyEnforcementInterceptor} published for this request. When no
 * whole-request decision was taken - the path is not in {@code application.opa.protected-paths},
 * or policy enforcement is switched off - the parameter resolves to
 * {@link DefaultPolicyDecisionOutput#ALLOW}. That is not a permission being granted here: a
 * request only reaches a handler if the PEP allowed it or never gated it, so the honest value for
 * "no whole-request decision was taken" is a verdict of allow with nothing filtered. Any
 * finer-grained filtering is the decision the service layer asks for per resource.
 */
@Component
@Slf4j
public class PolicyDecisionOutputArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return DefaultPolicyDecisionOutput.class.equals(parameter.getParameterType());
    }

    @Override
    public DefaultPolicyDecisionOutput resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        Object decision =
                webRequest.getAttribute(DefaultPolicyDecisionOutput.REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (decision instanceof DefaultPolicyDecisionOutput output) {
            return output;
        }
        log.debug(
                "No policy decision published for this request; resolving {} to ALLOW with nothing filtered",
                parameter.getParameterType().getSimpleName());
        return DefaultPolicyDecisionOutput.ALLOW;
    }
}
