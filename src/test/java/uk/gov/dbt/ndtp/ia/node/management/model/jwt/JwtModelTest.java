/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JwtModelTest {

    @Test
    void testEnhancedPrincipal() {
        EnhancedPrincipal principal = new EnhancedPrincipal("user123", "client456", "test-organisation");
        assertEquals("user123", principal.subject());
        assertEquals("client456", principal.clientId());
        assertEquals("test-organisation", principal.organisation());
        String toString = principal.toString();
        assertTrue(toString.contains("user123"));
        assertTrue(toString.contains("client456"));
        assertTrue(toString.contains("test-organisation"));
    }

    @Test
    void testJwtToken() {
        JwtToken token = JwtToken.builder()
                .sub("subject")
                .clientId("client")
                .organisation("FEDERATOR_ENV")
                .active(true)
                .aud(List.of("aud1"))
                .resourceAccess(Map.of(
                        "res",
                        JwtToken.ResourceAccess.builder()
                                .roles(List.of("role1"))
                                .build()))
                .build();

        assertEquals("subject", token.getSub());
        assertEquals("client", token.getClientId());
        assertEquals("FEDERATOR_ENV", token.getOrganisation());
        assertTrue(token.getActive());
        assertEquals(List.of("aud1"), token.getAud());
        assertNotNull(token.getResourceAccess());
        assertEquals(List.of("role1"), token.getResourceAccess().get("res").getRoles());

        // Exercise toString, equals, and hashCode via Lombok
        assertNotNull(token.toString());
        JwtToken token2 = JwtToken.builder()
                .sub("subject")
                .clientId("client")
                .organisation("FEDERATOR_ENV")
                .active(true)
                .aud(List.of("aud1"))
                .resourceAccess(Map.of(
                        "res",
                        JwtToken.ResourceAccess.builder()
                                .roles(List.of("role1"))
                                .build()))
                .build();
        assertEquals(token, token2);
        assertEquals(token.hashCode(), token2.hashCode());

        JwtToken emptyToken = new JwtToken();
        assertNotNull(emptyToken);
    }
}
