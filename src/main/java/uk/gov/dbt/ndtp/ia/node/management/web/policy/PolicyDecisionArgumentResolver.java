/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
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
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionDetails;

/**
 * Injects the PDP's decision into any controller method that declares an
 * {@code Optional<PolicyDecision<D>>} parameter, where {@code D} is the
 * {@link PolicyDecisionDetails} type named by the method's {@code @Policy(details = ...)} - for
 * example {@code Optional<PolicyDecision<ProductSubscriptionPolicyDecisionDetails>>} - so a handler
 * reads its rule's details typed, and can pass the decision on to the service layer instead of
 * re-deriving it.
 *
 * <p>Arguments are resolved before the handler method is invoked, and the decision is taken inside
 * that invocation - after method security - so this resolver supplies the parameter's slot and
 * {@link PolicyEnforcementInterceptor} fills it with the decision once the PDP has allowed the call.
 * The value is therefore present only when a decision was actually taken: policy enforcement is
 * switched on and the call carries {@link Policy}. Otherwise it is
 * empty - with {@code application.opa.enabled=false} no decision exists, and an empty value says so
 * rather than standing in a verdict nobody reached. {@link PolicyAnnotationValidator} refuses to
 * start when a handler asks for the decision without carrying {@link Policy}, or declares a details
 * type the annotation's could not be assigned to, since the parameter could then never hold the
 * decision. A handler therefore cannot mistake "policy is off" for "policy allowed this".
 *
 * <p>Only the {@code Optional} form is supported, so there is one way to ask for the decision and
 * every caller has to handle its absence.
 */
@Component
@Slf4j
public class PolicyDecisionArgumentResolver implements HandlerMethodArgumentResolver {

    private final boolean enabled;

    public PolicyDecisionArgumentResolver(OpaProperties opaProperties) {
        this.enabled = opaProperties.enabled();
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return isDecisionParameter(parameter);
    }

    /** Whether a handler parameter is an {@code Optional<PolicyDecision<...>>}. */
    static boolean isDecisionParameter(MethodParameter parameter) {
        return Optional.class.equals(parameter.getParameterType())
                && PolicyDecision.class.equals(ResolvableType.forMethodParameter(parameter)
                        .getGeneric(0)
                        .resolve());
    }

    /**
     * The details type a decision parameter declares: {@code D} in {@code Optional<PolicyDecision<D>>}.
     * A wildcard or raw declaration reads as the generic {@link PolicyDecisionDetails}.
     */
    static Class<?> declaredDetailsType(MethodParameter parameter) {
        return ResolvableType.forMethodParameter(parameter)
                .getGeneric(0)
                .getGeneric(0)
                .resolve(PolicyDecisionDetails.class);
    }

    @Override
    public Optional<PolicyDecision<?>> resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        if (!enabled) {
            return Optional.empty();
        }
        Object decision = webRequest.getAttribute(PolicyDecision.REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (decision instanceof PolicyDecision<?> output
                && declaredDetailsType(parameter).isInstance(output.details())) {
            return Optional.of(output);
        }
        // Normally there is nothing to find yet: arguments are resolved before the handler is
        // invoked, and the decision is taken inside that invocation. The enforcement point
        // overwrites this empty value with the decision before the method body runs, so this is
        // the expected path on an annotated endpoint and says nothing about the outcome. Look for
        // the interceptor's own ALLOW or DENY line for that.
        log.trace("No decision published yet; supplying an empty value for the enforcement point to fill");
        return Optional.empty();
    }
}
