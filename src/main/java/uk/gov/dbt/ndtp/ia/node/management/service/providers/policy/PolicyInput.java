/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Policy attributes describing who is making a request and what they are trying to do,
 * sent to the PDP (OPA) as the {@code input} of a decision request. {@code resource} and
 * {@code action} are opaque strings whose convention is caller-defined: the whole-request
 * PEP ({@link uk.gov.dbt.ndtp.ia.node.management.config.PolicyEnforcementInterceptor}) uses
 * the request URI and HTTP method; per-candidate callers (e.g. product discovery) may use a
 * different convention, such as a stable resource id and a named action.
 *
 * <p>{@code organisation} and {@code organisationId} are separate fields on purpose - see
 * {@link PolicyRequester} for why - so a policy can read either, or require both to agree.
 *
 * @param clientId identity of the calling client
 * @param organisation the token's {@code organisation} claim, if known
 * @param organisationId organisation row id resolved from the client certificate, if known
 * @param resource the resource being evaluated, in whatever convention the caller uses
 * @param action the action being evaluated, in whatever convention the caller uses
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyInput(String clientId, String organisation, String organisationId, String resource, String action) {

    /**
     * Builds an input for one decision about {@code resource}/{@code action} by {@code requester}.
     *
     * @param requester who is asking
     * @param resource the resource being evaluated
     * @param action the action being evaluated
     * @return the PDP input
     */
    public static PolicyInput of(PolicyRequester requester, String resource, String action) {
        return new PolicyInput(
                requester.clientId(), requester.organisation(), requester.organisationId(), resource, action);
    }
}
