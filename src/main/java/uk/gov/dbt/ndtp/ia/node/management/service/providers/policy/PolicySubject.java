/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Who is making the request, as the PDP sees it.
 *
 * <p>{@code kind} distinguishes a machine caller from a human one: tokens issued through the
 * client credentials grant carry a {@code service-account-*} username and are reported as
 * {@code service} with the client id as {@code userId}; any other token is reported as
 * {@code user} with its email (or username) as {@code userId}.
 *
 * <p>{@code clientId} and {@code organisation} are both taken from the authenticated principal
 * and are distinct facts: the client id names the OAuth client the token was issued to, while the
 * organisation names who that client acts for. Several clients may belong to one organisation, so
 * a policy may legitimately gate on either.
 *
 * @param kind {@code service} or {@code user}
 * @param userId the caller's identity, in whatever form {@code kind} implies
 * @param clientId the OAuth client the token was issued to, from the principal
 * @param token the token's claims, verbatim - never the encoded token itself
 * @param organisation the organisation this caller acts for
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicySubject(
        String kind,
        @JsonProperty("user_id") String userId,
        String clientId,
        Map<String, Object> token,
        PolicyOrganisation organisation) {

    /** Subject kind for a machine caller authenticated by the client credentials grant. */
    public static final String KIND_SERVICE = "service";

    /** Subject kind for a caller acting as a person. */
    public static final String KIND_USER = "user";
}
