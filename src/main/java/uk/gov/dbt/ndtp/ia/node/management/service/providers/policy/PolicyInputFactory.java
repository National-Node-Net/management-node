/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;
import uk.gov.dbt.ndtp.ia.node.management.model.UnknownIdentifiers;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.service.data.OrganisationService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeScopeCode;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeService;

/**
 * Assembles the {@link PolicyInput} sent to the PDP. This is the only place that knows where
 * each fact comes from - token claims, client certificate, database attributes or the servlet
 * request - so callers ask for a decision without restating how identity is sourced.
 *
 * <p>Headers are filtered against {@link OpaProperties#forwardedHeaders()}; {@code Authorization}
 * and {@code Cookie} are refused unconditionally, whatever the configuration says, since the
 * token's claims already travel in {@link PolicySubject#token()}.
 */
@Component
public class PolicyInputFactory {

    /** Headers never forwarded to the PDP, regardless of configuration. */
    private static final Set<String> NEVER_FORWARDED =
            Set.of("authorization", "cookie", "set-cookie", "proxy-authorization");

    private static final String SERVICE_ACCOUNT_USERNAME_PREFIX = "service-account-";

    /**
     * Sentinel {@code EnhancedPrincipal} uses when the token carries no {@code organisation}
     * claim. Treated as "no organisation" rather than forwarded, so a policy cannot come to
     * depend on a magic string that means the claim was missing.
     */
    private static final String CLAIM_PREFERRED_USERNAME = "preferred_username";

    private static final String CLAIM_EMAIL = "email";

    private final OrganisationService organisationService;
    private final PolicyAttributeService policyAttributeService;
    private final Set<String> forwardedHeaders;

    public PolicyInputFactory(
            OrganisationService organisationService,
            PolicyAttributeService policyAttributeService,
            OpaProperties opaProperties) {
        this.organisationService = organisationService;
        this.policyAttributeService = policyAttributeService;
        this.forwardedHeaders = opaProperties.forwardedHeaders() == null
                ? Set.of()
                : opaProperties.forwardedHeaders().stream()
                        .map(header -> header.toLowerCase(Locale.ROOT))
                        .filter(header -> !NEVER_FORWARDED.contains(header))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Builds the decision input for {@code request}, deciding about {@code target}.
     *
     * <p>The body is supplied by the caller rather than read from the request here: a servlet body
     * is a one-shot stream, so reading it would leave nothing for the handler to bind. A controller
     * passes the body it has already bound; an interceptor running before the handler passes the
     * body it buffered, or null.
     *
     * <p>The resource kind and action come from the target, never from the URL, so the rule the
     * PDP selects does not shift when a path is renamed or carries path variables.
     *
     * @param request the request being authorised
     * @param body the request body as policy attributes, or null for none
     * @param target the resource kind and action being decided
     * @return the PDP input
     */
    public PolicyInput create(HttpServletRequest request, Object body, PolicyTarget<?> target) {
        return new PolicyInput(
                buildSubject(request),
                target.action(),
                PolicyResource.ofKind(target.resource()),
                buildRequest(request, body));
    }

    private PolicySubject buildSubject(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        EnhancedPrincipal principal =
                authentication != null && authentication.getPrincipal() instanceof EnhancedPrincipal p ? p : null;
        Map<String, Object> claims = claimsOf(authentication);
        return new PolicySubject(
                kindOf(claims),
                userIdOf(claims, principal),
                principal != null ? principal.clientId() : stringClaim(claims, "client_id"),
                claims,
                buildOrganisation(principal));
    }

    /**
     * A client credentials token names its service account in {@code preferred_username}; any
     * other token is treated as acting for a person.
     */
    private String kindOf(Map<String, Object> claims) {
        Object username = claims.get(CLAIM_PREFERRED_USERNAME);
        boolean serviceAccount = username instanceof String name && name.startsWith(SERVICE_ACCOUNT_USERNAME_PREFIX);
        return serviceAccount ? PolicySubject.KIND_SERVICE : PolicySubject.KIND_USER;
    }

    private String userIdOf(Map<String, Object> claims, EnhancedPrincipal principal) {
        if (PolicySubject.KIND_SERVICE.equals(kindOf(claims))) {
            return principal != null ? principal.clientId() : stringClaim(claims, "client_id");
        }
        String email = stringClaim(claims, CLAIM_EMAIL);
        if (email != null) {
            return email;
        }
        String username = stringClaim(claims, CLAIM_PREFERRED_USERNAME);
        return username != null ? username : (principal != null ? principal.subject() : null);
    }

    /**
     * Takes the organisation key from the principal's {@code organisation} claim, then reads that
     * organisation's {@code ORGANISATION}-scoped policy attributes from the database. A key that
     * matches no organisation row still reaches the PDP, carrying no attributes: the policy can
     * then decide what an unknown organisation means, rather than the decision failing here.
     */
    private PolicyOrganisation buildOrganisation(EnhancedPrincipal principal) {
        String key = principal == null ? null : principal.organisation();
        if (key == null || key.isBlank() || UnknownIdentifiers.UNKNOWN_ORG.equals(key)) {
            return PolicyOrganisation.empty();
        }
        Map<String, Object> attributes = organisationService
                .findIdByKey(key)
                .map(id -> policyAttributeService.findAttributeMap(id, PolicyAttributeScopeCode.ORGANISATION))
                .orElseGet(Map::of);
        return new PolicyOrganisation(key, attributes);
    }

    private PolicyHttpRequest buildRequest(HttpServletRequest request, Object body) {
        return new PolicyHttpRequest(
                headersOf(request), queryOf(request), request.getRequestURI(), request.getMethod(), body);
    }

    private Map<String, String> headersOf(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (forwardedHeaders.contains(lower)) {
                headers.put(lower, request.getHeader(name));
            }
        }
        return headers;
    }

    private Map<String, List<String>> queryOf(HttpServletRequest request) {
        return QueryParameters.of(request.getQueryString());
    }

    private Map<String, Object> claimsOf(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            return Map.of();
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        jwtAuthentication.getToken().getClaims().forEach((name, value) -> claims.put(name, normaliseClaim(value)));
        return claims;
    }

    /**
     * Keeps timestamp claims as the epoch seconds they arrived as, so a policy comparing
     * {@code exp} or {@code iat} sees a number rather than a serialised {@code Instant}.
     */
    private Object normaliseClaim(Object value) {
        return value instanceof Instant instant ? instant.getEpochSecond() : value;
    }

    private String stringClaim(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
