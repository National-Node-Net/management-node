/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the OPA Policy Decision Point (PDP). Which endpoints are enforced is not
 * configured here: a handler opts in with {@code @Policy}.
 *
 * @param url base URL of the OPA server (e.g. http://localhost:8181)
 * @param decisionPath path appended to the base URL for the decision query (e.g. /v1/data/dispatch/decision)
 * @param connectTimeout maximum time to wait to establish a connection
 * @param enabled master switch for policy enforcement. Defaults to {@code false}: with OPA off the
 *     PEP is not registered and every decision returns ALLOW, so no policy is evaluated at all. Must
 *     be set to {@code true} in any environment that relies on policy to restrict access.
 * @param readTimeout maximum time to wait for a response
 * @param forwardedHeaders request headers allowed into {@code input.request.headers}
 * @param logInput whether every decision input is written to the log before it is sent to the PDP.
 *     Defaults to {@code false}: the input carries the token's claims verbatim, so this is a
 *     diagnostic aid for development and must stay off where logs are retained or shipped.
 * @param logOutput whether every decision the PDP returns is written to the log. Defaults to
 *     {@code false}. Unlike {@code logInput}, the decision document does not carry the caller's
 *     token claims, but product discovery still asks for one decision per candidate product, so
 *     this remains a diagnostic aid rather than something to leave on.
 */
@ConfigurationProperties(prefix = "application.opa")
public record OpaProperties(
        boolean enabled,
        String url,
        String decisionPath,
        Duration connectTimeout,
        Duration readTimeout,
        List<String> forwardedHeaders,
        boolean logInput,
        boolean logOutput) {}
