/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import uk.gov.dbt.ndtp.ia.node.management.web.certificate.CertificateValidationInterceptor;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.PolicyDecisionOutputArgumentResolver;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.PolicyEnforcementInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CertificateValidationInterceptor certificateValidationInterceptor;
    private final PolicyEnforcementInterceptor policyEnforcementInterceptor;
    private final PolicyDecisionOutputArgumentResolver policyDecisionOutputArgumentResolver;
    private final OpaProperties opaProperties;

    public WebConfig(
            CertificateValidationInterceptor certificateValidationInterceptor,
            PolicyEnforcementInterceptor policyEnforcementInterceptor,
            PolicyDecisionOutputArgumentResolver policyDecisionOutputArgumentResolver,
            OpaProperties opaProperties) {
        this.certificateValidationInterceptor = certificateValidationInterceptor;
        this.policyEnforcementInterceptor = policyEnforcementInterceptor;
        this.policyDecisionOutputArgumentResolver = policyDecisionOutputArgumentResolver;
        this.opaProperties = opaProperties;
    }

    /**
     * Registered whether or not policy enforcement is on: with OPA off no decision is published,
     * and the resolver answers with a permissive decision rather than leaving the parameter
     * unresolvable and the handler unmappable.
     */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(policyDecisionOutputArgumentResolver);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(certificateValidationInterceptor).addPathPatterns("/api/v1/configuration/**");

        List<String> protectedPaths = opaProperties.protectedPaths();
        if (!protectedPaths.isEmpty() && opaProperties.enabled()) {
            registry.addInterceptor(policyEnforcementInterceptor).addPathPatterns(protectedPaths);
        }
    }
}
