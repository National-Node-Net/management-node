/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;

class CustomJwtAuthenticationTokenTest {

    private Jwt jwt;
    private EnhancedPrincipal principal;
    private Collection<SimpleGrantedAuthority> authorities;

    @BeforeEach
    void setUp() {
        Map<String, Object> headers = new HashMap<>();
        headers.put("alg", "RS256");
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "test-subject");
        jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(3600), headers, claims);
        principal = new EnhancedPrincipal("test-subject", "test-client", "test-organisation");
        authorities = Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Test
    void constructorAndGetPrincipal() {
        CustomJwtAuthenticationToken token = new CustomJwtAuthenticationToken(jwt, authorities, principal);
        assertEquals(principal, token.getPrincipal());
        assertEquals(jwt, token.getToken());
        assertEquals(authorities, token.getAuthorities());
    }

    @Test
    void equalsAndHashCode() {
        CustomJwtAuthenticationToken token1 = new CustomJwtAuthenticationToken(jwt, authorities, principal);
        CustomJwtAuthenticationToken token2 = new CustomJwtAuthenticationToken(jwt, authorities, principal);

        EnhancedPrincipal principal2 = new EnhancedPrincipal("other-subject", "test-client", "test-organisation");
        CustomJwtAuthenticationToken token3 = new CustomJwtAuthenticationToken(jwt, authorities, principal2);

        // Equals
        assertEquals(token1, token2);
        assertNotEquals(token1, token3);
        assertNotEquals(null, token1);

        // HashCode
        assertEquals(token1.hashCode(), token2.hashCode());
        assertNotEquals(token1.hashCode(), token3.hashCode());
    }
}
