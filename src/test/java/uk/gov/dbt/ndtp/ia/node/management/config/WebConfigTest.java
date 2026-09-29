/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.PolicyDecisionArgumentResolver;

/**
 * Covers the MVC wiring left in {@link WebConfig}: the decision parameter resolver. The certificate
 * check and the Policy Enforcement Point are method advice ordered after method security, covered by
 * {@code RequestEnforcementConfigTest}.
 */
class WebConfigTest {

    private final PolicyDecisionArgumentResolver resolver = mock(PolicyDecisionArgumentResolver.class);
    private final WebConfig config = new WebConfig(resolver);

    @Test
    void decisionParameterResolver_isRegistered() {
        List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();

        config.addArgumentResolvers(resolvers);

        assertThat(resolvers).containsExactly(resolver);
    }

    /** Handler interceptors run before @PreAuthorize, so no enforcement may be registered as one. */
    @Test
    void noHandlerInterceptorsAreRegistered() {
        InterceptorRegistry registry = mock(InterceptorRegistry.class);

        config.addInterceptors(registry);

        verifyNoInteractions(registry);
    }
}
