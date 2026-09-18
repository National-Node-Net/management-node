/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.PolicyDecisionArgumentResolver;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PolicyDecisionArgumentResolver policyDecisionArgumentResolver;

    public WebConfig(PolicyDecisionArgumentResolver policyDecisionArgumentResolver) {
        this.policyDecisionArgumentResolver = policyDecisionArgumentResolver;
    }

    /**
     * Registered whether or not policy enforcement is on, so a handler asking for the decision is
     * always resolvable; with OPA off the parameter is simply empty.
     *
     * <p>The certificate check and the Policy Enforcement Point are not handler interceptors: they
     * must run after {@code @PreAuthorize}, which only method advice can do. See
     * {@link RequestEnforcementConfig}.
     */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(policyDecisionArgumentResolver);
    }
}
