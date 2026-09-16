/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.policy;

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.DefaultPolicyDecisionOutput;

/**
 * Injects the PDP's decision into any controller method that declares an
 * {@code Optional<DefaultPolicyDecisionOutput>} parameter, so a handler can pass the decision on to
 * the service layer instead of re-deriving it.
 *
 * <p>The value is present only when a decision was actually taken: policy enforcement is switched
 * on and {@link PolicyEnforcementInterceptor} published one for this request. Otherwise it is
 * empty - with {@code application.opa.enabled=false}, or on a path outside
 * {@code application.opa.protected-paths}, no decision exists, and an empty value says so rather
 * than standing in a verdict nobody reached. A handler therefore cannot mistake "policy is off" for
 * "policy allowed this".
 *
 * <p>Only the {@code Optional} form is supported, so there is one way to ask for the decision and
 * every caller has to handle its absence.
 */
@Component
@Slf4j
public class PolicyDecisionOutputArgumentResolver implements HandlerMethodArgumentResolver {

    private final boolean enabled;

    public PolicyDecisionOutputArgumentResolver(OpaProperties opaProperties) {
        this.enabled = opaProperties.enabled();
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Optional.class.equals(parameter.getParameterType())
                && DefaultPolicyDecisionOutput.class.equals(ResolvableType.forMethodParameter(parameter)
                        .getGeneric(0)
                        .resolve());
    }

    @Override
    public Optional<DefaultPolicyDecisionOutput> resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        if (!enabled) {
            return Optional.empty();
        }
        Object decision =
                webRequest.getAttribute(DefaultPolicyDecisionOutput.REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (decision instanceof DefaultPolicyDecisionOutput output) {
            return Optional.of(output);
        }
        log.debug("No policy decision published for this request; resolving to an empty decision");
        return Optional.empty();
    }
}
