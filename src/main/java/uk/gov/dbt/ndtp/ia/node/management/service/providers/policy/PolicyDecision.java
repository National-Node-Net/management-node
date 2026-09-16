/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

/**
 * The verdict half of a PDP (OPA) policy evaluation - the allow/deny label the enforcement
 * points branch and log on. The full decision, including the attribute lists, is
 * {@link DefaultPolicyDecisionOutput}.
 */
public enum PolicyDecision {
    ALLOW,
    DENY;

    /**
     * @param allow whether the action is permitted
     * @return {@link #ALLOW} if permitted, otherwise {@link #DENY}
     */
    public static PolicyDecision of(boolean allow) {
        return allow ? ALLOW : DENY;
    }
}
