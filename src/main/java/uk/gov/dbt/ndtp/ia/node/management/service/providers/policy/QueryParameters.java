/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a raw query string into the parameters a policy sees.
 *
 * <p>Deliberately reads the query string rather than the servlet parameters: for a form-encoded
 * POST the container merges the posted fields into {@code getParameterMap}, which would put body
 * content into {@code input.request.query}. Keeping the two apart means each field of the policy
 * input means exactly what its name says.
 *
 * <p>A repeated parameter keeps every value, and values are percent-decoded, so
 * {@code ?tag=a&tag=b&q=two%20words} yields {@code {tag: [a, b], q: [two words]}}.
 */
final class QueryParameters {

    private QueryParameters() {}

    /**
     * @param queryString the raw query string, without the leading {@code ?}; may be null
     * @return parameter name to values, never null
     */
    static Map<String, List<String>> of(String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return Map.of();
        }
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String name = decode(separator < 0 ? pair : pair.substring(0, separator));
            if (name.isEmpty()) {
                continue;
            }
            String value = separator < 0 ? "" : decode(pair.substring(separator + 1));
            parameters.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        }
        parameters.replaceAll((name, values) -> List.copyOf(values));
        return parameters;
    }

    /** Percent-decodes one component, leaving it as-is if it is not validly encoded. */
    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
