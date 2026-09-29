/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.dbt.ndtp.ia.node.management.web.certificate.CertificateValidationInterceptor;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.Policy;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.PolicyEnforcementInterceptor;

/**
 * Places the certificate check and the Policy Enforcement Point in the same advice chain as method
 * security, and after it, so a request is judged in this order:
 *
 * <ol>
 *   <li>authentication - the security filter chain, before any controller is reached;
 *   <li>authorization - {@code @PreAuthorize} and the other pre-invocation method security;
 *   <li>the organisation certificate check;
 *   <li>the policy decision.
 * </ol>
 *
 * A caller refused at any step is never passed to the next, so the PDP is not consulted for a
 * caller who lacks the endpoint's role.
 *
 * <p>Handler interceptors cannot give this order: they run before the handler method is invoked,
 * and method security runs as part of that invocation. Advisors are sorted with Spring Security's
 * own method interceptors by {@code order}, so both are placed after the last pre-invocation
 * authorization step. The advisors are infrastructure beans, as Spring Security's are, and resolve
 * their interceptors lazily so creating them does not pull the application's beans into
 * existence before the post-processors that proxy those beans.
 */
@Configuration(proxyBeanMethods = false)
public class RequestEnforcementConfig {

    /** After {@code @PreAuthorize}, {@code @Secured} and JSR-250 checks; before post-invocation authorization. */
    public static final int CERTIFICATE_VALIDATION_ORDER = AuthorizationInterceptorsOrder.JSR250.getOrder() + 10;

    /** After the certificate check, so a request without a valid certificate never reaches the PDP. */
    public static final int POLICY_ENFORCEMENT_ORDER = CERTIFICATE_VALIDATION_ORDER + 10;

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static Advisor certificateValidationAdvisor(ObjectProvider<CertificateValidationInterceptor> interceptor) {
        // Every controller: the interceptor itself decides by path which requests need a certificate.
        return advisor(
                new AnnotationMatchingPointcut(RestController.class, true),
                invocation -> interceptor.getObject().invoke(invocation),
                CERTIFICATE_VALIDATION_ORDER);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static Advisor policyEnforcementAdvisor(ObjectProvider<PolicyEnforcementInterceptor> interceptor) {
        return advisor(
                new AnnotationMatchingPointcut(null, Policy.class, true),
                invocation -> interceptor.getObject().invoke(invocation),
                POLICY_ENFORCEMENT_ORDER);
    }

    private static Advisor advisor(AnnotationMatchingPointcut pointcut, MethodInterceptor interceptor, int order) {
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(pointcut, interceptor);
        advisor.setOrder(order);
        return advisor;
    }
}
