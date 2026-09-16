/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import java.util.List;
import java.util.Map;

/** Builds representative {@link PolicyInput} values so tests do not restate the nested shape. */
public final class PolicyInputFixture {

    private PolicyInputFixture() {}

    /** An input for {@code clientId} calling {@code action} on {@code kind}, with no resource id. */
    public static PolicyInput of(String clientId, String kind, String action) {
        return of(clientId, kind, action, "/api/v1/" + kind + "/" + action);
    }

    /** As {@link #of(String, String, String)}, with an explicit request path. */
    public static PolicyInput of(String clientId, String kind, String action, String path) {
        return of(clientId, kind, action, path, "POST");
    }

    /** As {@link #of(String, String, String, String)}, with an explicit HTTP method. */
    public static PolicyInput of(String clientId, String kind, String action, String path, String method) {
        PolicySubject subject = new PolicySubject(
                PolicySubject.KIND_SERVICE,
                clientId,
                clientId,
                Map.of("client_id", clientId),
                new PolicyOrganisation("FEDERATOR_ENV", Map.of()));
        PolicyHttpRequest request = new PolicyHttpRequest(Map.of(), Map.<String, List<String>>of(), path, method, null);
        return new PolicyInput(subject, action, PolicyResource.ofKind(kind), request);
    }
}
