/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Finds the id of the entity a request is about, wherever the endpoint happens to carry it.
 *
 * <p>An endpoint may name its entity in the path ({@code GET /api/v1/product/{productId}}) or in
 * the body ({@code POST /api/v1/product/subscribe}). Which one it uses is a REST design choice and
 * says nothing about whether policy should know the entity, so the extractor looks in both and
 * applies one naming convention to each:
 *
 * <ol>
 *   <li>path variable {@code <kind>Id}, then plain {@code id}
 *   <li>body field {@code <kind>Id}, then plain {@code id}
 * </ol>
 *
 * <p>The convention is what makes this consistent rather than a list of special cases: a new
 * resource kind needs no change here, because {@code consumerId} and {@code producerId} are found
 * by the same rule that finds {@code productId}. The path is searched first because a path variable
 * identifies the entity the request is *for*, while a body may mention several entities.
 *
 * <p>Finding an id does not by itself load anything. Whether the entity is read is declared per
 * endpoint by {@code @Policy(loadResource = true)}, so an endpoint pays for a read only when a rule
 * uses what it returns.
 */
@Component
public class PolicyResourceIdExtractor {

    private static final String ID = "id";

    /**
     * The entity id this request names, or empty when it names none.
     *
     * @param resourceKind the resource kind being decided, e.g. {@code product}
     * @param request the request being authorised
     * @param body the parsed request body, or null when there is none
     */
    public Optional<String> extract(String resourceKind, HttpServletRequest request, Object body) {
        String conventional = resourceKind + "Id";
        return fromPath(request, conventional)
                .or(() -> fromPath(request, ID))
                .or(() -> fromBody(body, conventional))
                .or(() -> fromBody(body, ID));
    }

    /**
     * Spring records the URI template variables on the request while mapping the handler, which
     * happens before the handler is invoked; the enforcement point runs inside that invocation, so
     * they are available by the time a decision is made.
     */
    private Optional<String> fromPath(HttpServletRequest request, String name) {
        if (request == null) {
            return Optional.empty();
        }
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(variables instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        return text(map.get(name));
    }

    private Optional<String> fromBody(Object body, String name) {
        return body instanceof Map<?, ?> map ? text(map.get(name)) : Optional.empty();
    }

    /** Ids arrive as numbers in JSON and as text in a path, so both are read as text here. */
    private Optional<String> text(Object raw) {
        return switch (raw) {
            case null -> Optional.empty();
            case String s -> s.isBlank() ? Optional.empty() : Optional.of(s.trim());
            case Number n -> Optional.of(String.valueOf(n));
            default -> Optional.empty();
        };
    }
}
