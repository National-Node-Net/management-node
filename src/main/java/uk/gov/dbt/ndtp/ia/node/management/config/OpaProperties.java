/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Connection settings for the OPA Policy Decision Point (PDP), and the set of API path
 * patterns the Policy Enforcement Point protects.
 *
 * @param url base URL of the OPA server (e.g. http://localhost:8181)
 * @param decisionPath path appended to the base URL for the decision query (e.g. /v1/data/management_node/allow)
 * @param connectTimeout maximum time to wait to establish a connection
 * @param enabled master switch for policy enforcement. Defaults to {@code false}: with OPA off the
 *     PEP is not registered and every decision returns ALLOW, so no policy is evaluated at all. Must
 *     be set to {@code true} in any environment that relies on policy to restrict access.
 * @param readTimeout maximum time to wait for a response
 * @param protectedPaths Spring MVC path patterns (e.g. /api/v1/configuration/**) that the PEP intercepts.
 *     Required and non-empty: Spring's {@code MappedInterceptor} treats an empty include-pattern list
 *     as "match every path" rather than "match nothing", so this must never be silently absent.
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
@Validated
public record OpaProperties(
        boolean enabled,
        String url,
        String decisionPath,
        Duration connectTimeout,
        Duration readTimeout,
        @NotEmpty List<String> protectedPaths,
        List<String> forwardedHeaders,
        boolean logInput,
        boolean logOutput) {}
