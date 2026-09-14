/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

/**
 * Who is making a request, as far as the PDP is concerned. Groups the three identity values
 * so they travel together rather than as interchangeable {@code String} parameters, and so a
 * caller cannot silently transpose the two organisation values.
 *
 * <p>The two organisation fields are deliberately distinct and independently sourced:
 * {@code organisation} comes from the access token, {@code organisationId} from the client
 * certificate. They answer different questions ("which organisation does the IdP say issued
 * this token" versus "which organisation row does this certificate belong to"), and a policy
 * may legitimately require both, or cross-check one against the other.
 *
 * @param clientId identity of the calling client, from the token's {@code azp}/{@code client_id}
 * @param organisation the token's {@code organisation} claim (e.g. {@code FEDERATOR_ENV}), or
 *     {@code unknown_organisation} when the token carries no such claim
 * @param organisationId id of the {@code organisation} row resolved from the client certificate
 *     by {@link uk.gov.dbt.ndtp.ia.node.management.config.CertificateValidationInterceptor}, or
 *     {@code null} on a request that did not go through certificate validation
 */
public record PolicyRequester(String clientId, String organisation, String organisationId) {}
