/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * The HTTP request that triggered the decision, for policies that need to reason about the
 * call itself rather than only about who is calling and what they are touching.
 *
 * <p>{@code headers} is deliberately not the full header set: only headers on the configured
 * allow list are forwarded, and {@code Authorization} and {@code Cookie} are never among them.
 * The PDP already receives the token's claims through {@link PolicySubject#token()}, so
 * forwarding the credential itself would add nothing but exposure.
 *
 * @param headers allow-listed request headers, lower-cased, never null
 * @param query query parameters; a repeated parameter keeps all its values, never null
 * @param path the request path (e.g. {@code /api/v1/product/discover})
 * @param method the HTTP method (e.g. {@code POST})
 * @param body the parsed request body when the caller supplies one, otherwise null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyHttpRequest(
        Map<String, String> headers, Map<String, List<String>> query, String path, String method, Object body) {}
