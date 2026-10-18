/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.jwt;

import java.io.Serial;
import java.io.Serializable;

/**
 * Custom Principal object that includes clientId and organisation information from the JWT.
 *
 * @param subject      -- GETTER --
 *                     Get the subject (user identifier)
 * @param clientId     -- GETTER --
 *                     Get the client ID
 * @param organisation -- GETTER --
 *                     Get the organisation the token was issued for, taken from the
 *                     {@code organisation} claim. Never null: falls back to
 *                     {@code UNKNOWN_ORG} when the claim is absent, so callers
 *                     do not have to null-check a value that is always present in the
 *                     token shape this node expects.
 */
public record EnhancedPrincipal(String subject, String clientId, String organisation) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public String toString() {
        return "CustomPrincipal{" + "subject='" + subject + '\'' + ", clientId='" + clientId + '\'' + ", organisation='"
                + organisation + '\'' + '}';
    }
}
