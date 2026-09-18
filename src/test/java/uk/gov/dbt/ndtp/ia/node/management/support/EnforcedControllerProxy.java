/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import uk.gov.dbt.ndtp.ia.node.management.config.RequestEnforcementConfig;
import uk.gov.dbt.ndtp.ia.node.management.web.certificate.CertificateValidationInterceptor;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.PolicyEnforcementInterceptor;

/**
 * Proxies a controller with the same advice chain the application builds: Spring Security's
 * {@code @PreAuthorize} interceptor plus the advisors from {@link RequestEnforcementConfig}, sorted
 * by their order exactly as the auto-proxy creator sorts them. Lets a standalone MockMvc test
 * exercise the real enforcement order without starting an application context.
 */
public final class EnforcedControllerProxy {

    private EnforcedControllerProxy() {}

    public static <T> T of(
            T controller,
            CertificateValidationInterceptor certificateValidation,
            PolicyEnforcementInterceptor policyEnforcement) {
        List<Advisor> advisors = new ArrayList<>(List.of(
                AuthorizationManagerBeforeMethodInterceptor.preAuthorize(),
                RequestEnforcementConfig.certificateValidationAdvisor(
                        provider(CertificateValidationInterceptor.class, certificateValidation)),
                RequestEnforcementConfig.policyEnforcementAdvisor(
                        provider(PolicyEnforcementInterceptor.class, policyEnforcement))));
        AnnotationAwareOrderComparator.sort(advisors);

        ProxyFactory factory = new ProxyFactory(controller);
        factory.setProxyTargetClass(true);
        advisors.forEach(factory::addAdvisor);
        @SuppressWarnings("unchecked")
        T proxy = (T) factory.getProxy();
        return proxy;
    }

    private static <B> ObjectProvider<B> provider(Class<B> type, B bean) {
        return new StaticListableBeanFactory(Map.of(type.getSimpleName(), bean)).getBeanProvider(type);
    }
}
