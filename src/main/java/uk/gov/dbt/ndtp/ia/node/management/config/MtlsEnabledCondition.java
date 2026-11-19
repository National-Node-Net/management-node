/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when Tomcat authenticates clients with mTLS: {@code server.ssl.enabled} is true (the default) and
 * {@code server.ssl.client-auth} is {@code need} or {@code want}. See {@link RevocationEnforcementMode}.
 */
class MtlsEnabledCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        RevocationEnforcementMode mode = RevocationEnforcementMode.resolveTransport(context.getEnvironment());
        ConditionMessage message =
                ConditionMessage.forCondition(ConditionalOnMtlsEnabled.class).because(mode.reason());
        return mode.isActive() ? ConditionOutcome.match(message) : ConditionOutcome.noMatch(message);
    }
}
