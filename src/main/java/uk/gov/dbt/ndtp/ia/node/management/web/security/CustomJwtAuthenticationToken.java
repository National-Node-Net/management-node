/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.security;

import java.util.Collection;
import java.util.Objects;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;

/**
 * Custom JWT Authentication Token that uses CustomPrincipal as the principal object.
 * This allows access to the clientId in addition to the standard JWT information.
 */
public class CustomJwtAuthenticationToken extends JwtAuthenticationToken {

    private final EnhancedPrincipal principal;

    /**
     * Constructs a CustomJwtAuthenticationToken with the provided JWT, authorities, and CustomPrincipal.
     *
     * @param jwt         the JWT
     * @param authorities the collection of granted authorities
     * @param principal   the custom principal containing subject and clientId
     */
    public CustomJwtAuthenticationToken(
            Jwt jwt, Collection<? extends GrantedAuthority> authorities, EnhancedPrincipal principal) {
        super(jwt, authorities, principal.subject());
        this.principal = principal;
    }

    @Override
    public EnhancedPrincipal getPrincipal() {
        return this.principal;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        CustomJwtAuthenticationToken that = (CustomJwtAuthenticationToken) o;
        return Objects.equals(getPrincipal(), that.getPrincipal());
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), getPrincipal());
    }
}
