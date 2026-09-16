/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * The organisation a request is attributed to, as the PDP sees it.
 *
 * <p>{@code key} is the organisation the token was issued for, taken from the principal's
 * {@code organisation} claim. {@code attributes} are the live {@code ORGANISATION}-scoped policy
 * attribute values held against that organisation, keyed by attribute name with their JSON type
 * preserved - see {@link uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeService}.
 *
 * <p>The two travel together but are resolved in two steps: the key comes from the token, and
 * the attributes are read from the database row that key identifies. A token naming an
 * organisation the database does not hold yields the key with no attributes, rather than failing.
 *
 * @param key the organisation's key from the token (e.g. {@code ORG_A}), or null when the
 *     token names no organisation
 * @param attributes organisation-scoped policy attributes, never null (empty when none apply)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyOrganisation(String key, Map<String, Object> attributes) {

    /** An organisation with no resolved key and no attributes. */
    public static PolicyOrganisation empty() {
        return new PolicyOrganisation(null, Map.of());
    }
}
