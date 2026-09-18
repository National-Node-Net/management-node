/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.dbt.ndtp.ia.node.management.web.certificate.CertificateValidationInterceptor;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.Policy;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.PolicyEnforcementInterceptor;

/**
 * Pins where the certificate check and the Policy Enforcement Point sit in the method advice chain:
 * after every pre-invocation authorization step, certificate before policy, and before post-invocation
 * authorization - and which methods each applies to.
 */
class RequestEnforcementConfigTest {

    @RestController
    static class SampleController {

        @Policy(resource = "product", action = "view")
        public void enforced() {}

        public void unenforced() {}
    }

    static class NotAController {

        @Policy(resource = "product", action = "view")
        public void enforced() {}
    }

    private final CertificateValidationInterceptor certificateInterceptor =
            mock(CertificateValidationInterceptor.class);
    private final PolicyEnforcementInterceptor policyInterceptor = mock(PolicyEnforcementInterceptor.class);

    private final Advisor certificateAdvisor = RequestEnforcementConfig.certificateValidationAdvisor(
            provider(CertificateValidationInterceptor.class, certificateInterceptor));
    private final Advisor policyAdvisor = RequestEnforcementConfig.policyEnforcementAdvisor(
            provider(PolicyEnforcementInterceptor.class, policyInterceptor));

    private static <B> ObjectProvider<B> provider(Class<B> type, B bean) {
        return new StaticListableBeanFactory(Map.of(type.getSimpleName(), bean)).getBeanProvider(type);
    }

    private static int orderOf(Advisor advisor) {
        return ((Ordered) advisor).getOrder();
    }

    @Test
    void bothRunAfterEveryPreInvocationAuthorizationStep() {
        for (AuthorizationInterceptorsOrder before : List.of(
                AuthorizationInterceptorsOrder.PRE_FILTER,
                AuthorizationInterceptorsOrder.PRE_AUTHORIZE,
                AuthorizationInterceptorsOrder.SECURED,
                AuthorizationInterceptorsOrder.JSR250)) {
            assertThat(orderOf(certificateAdvisor)).isGreaterThan(before.getOrder());
            assertThat(orderOf(policyAdvisor)).isGreaterThan(before.getOrder());
        }
    }

    @Test
    void certificateRunsBeforePolicy_andBothBeforePostInvocationAuthorization() {
        assertThat(orderOf(certificateAdvisor)).isLessThan(orderOf(policyAdvisor));
        assertThat(orderOf(policyAdvisor)).isLessThan(AuthorizationInterceptorsOrder.SECURE_RESULT.getOrder());
    }

    /** Sorted the way the auto-proxy creator sorts a bean's advisors. */
    @Test
    void sortedTogetherWithMethodSecurity_preAuthorizeComesFirst() {
        Advisor preAuthorize = AuthorizationManagerBeforeMethodInterceptor.preAuthorize();
        List<Advisor> chain = new ArrayList<>(List.of(policyAdvisor, certificateAdvisor, preAuthorize));

        AnnotationAwareOrderComparator.sort(chain);

        assertThat(chain).containsExactly(preAuthorize, certificateAdvisor, policyAdvisor);
    }

    @Test
    void policyAdvisor_appliesOnlyToMethodsCarryingPolicy() throws NoSuchMethodException {
        PointcutAdvisor advisor = (PointcutAdvisor) policyAdvisor;

        assertThat(AopUtils.canApply(advisor, SampleController.class)).isTrue();
        assertThat(advisor.getPointcut()
                        .getMethodMatcher()
                        .matches(SampleController.class.getMethod("enforced"), SampleController.class))
                .isTrue();
        assertThat(advisor.getPointcut()
                        .getMethodMatcher()
                        .matches(SampleController.class.getMethod("unenforced"), SampleController.class))
                .isFalse();
    }

    @Test
    void certificateAdvisor_appliesToControllersOnly() {
        PointcutAdvisor advisor = (PointcutAdvisor) certificateAdvisor;

        assertThat(AopUtils.canApply(advisor, SampleController.class)).isTrue();
        assertThat(AopUtils.canApply(advisor, NotAController.class)).isFalse();
    }

    /** Creating the advisors must not create the interceptors, which depend on application beans. */
    @Test
    void advisors_resolveTheirInterceptorsLazily() {
        verifyNoInteractions(certificateInterceptor, policyInterceptor);
    }
}
