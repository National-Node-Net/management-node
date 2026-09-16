/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import uk.gov.dbt.ndtp.ia.node.management.exception.ResourceAccessParsingException;
import uk.gov.dbt.ndtp.ia.node.management.exception.TokenIntrospectionException;
import uk.gov.dbt.ndtp.ia.node.management.model.UnknownIdentifiers;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.JwtToken;

/**
 * The KeycloakJwtAuthenticationConverter class is responsible for converting a JWT token
 * into an authentication token by integrating with Keycloak-specific token introspection
 * and resource access information. This class ensures that roles, client ID, and other
 * relevant token claims are parsed accurately to produce an appropriate authentication
 * object.
 * <p>
 * This class implements the {@link Converter} interface to provide conversion functionality
 * between a {@link Jwt} and an instance of {@link AbstractAuthenticationToken}.
 * <p>
 * Key responsibilities include:
 * - Performing token introspection via the configured Keycloak introspection endpoint.
 * - Extracting authorities, roles, and resource access data from the token.
 * - Handling potential fallback scenarios when introspection fails.
 * - Processing key JWT claims such as "azp", "client_id", "sub", and resource-access roles.
 * <p>
 * Introspection is handled through HTTP calls using {@link RestTemplate} to ensure that
 * the token's validity and associated claims are verified against the configured Keycloak
 * server.
 * <p>
 * Thread Safety:
 * This class is designed to be thread-safe as it does not maintain mutable state at
 * the instance level. Configuration properties are injected as immutable fields and are
 * not modified during token conversion operations.
 */
@Component
@Slf4j
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    // Constants for claim names
    private static final String CLAIM_AZP = "azp";
    private static final String CLAIM_CLIENT_ID = "client_id";
    private static final String CLAIM_RESOURCE_ACCESS = "resource_access";
    private static final String CLAIM_ORGANISATION = "organisation";
    private static final String CLAIM_ROLES = "roles";

    // Constants for role prefixes and default values
    private static final String ROLE_PREFIX = "ROLE_";
    private static final String RESOURCE_ROLE_SEPARATOR = ":";

    // Form data keys
    private static final String FORM_CLIENT_ID = "client_id";
    private static final String FORM_TOKEN = "token";

    private final JwtGrantedAuthoritiesConverter defaultGrantedAuthoritiesConverter =
            new JwtGrantedAuthoritiesConverter();
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${spring.security.oauth2.resourceserver.opaquetoken.introspection-uri}")
    private String introspectionUri;

    /**
     * Performs token introspection by making an HTTP call to the introspection endpoint.
     *
     * @param tokenValue The JWT token value to introspect
     * @return JwtToken containing the introspection data
     * @throws TokenIntrospectionException If the introspection request fails or returns invalid data
     */
    private JwtToken performTokenIntrospection(String tokenValue, String clientId) throws TokenIntrospectionException {
        try {
            // Prepare headers for the introspection request
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            // Prepare form data for the introspection request
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add(FORM_CLIENT_ID, clientId);
            formData.add(FORM_TOKEN, tokenValue);

            // Create the request entity
            HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(formData, headers);

            log.debug("Performing token introspection for client ID: {}", clientId);

            // Make the introspection request
            ResponseEntity<JwtToken> response =
                    restTemplate.postForEntity(introspectionUri, requestEntity, JwtToken.class);

            // Parse the response
            JwtToken introspectionData = response.getBody();

            // Check if token is active
            if (introspectionData == null || !Boolean.TRUE.equals(introspectionData.getActive())) {
                log.error("Token introspection failed: Token is not active for client ID: {}", clientId);
                throw new TokenIntrospectionException("Token is not active", clientId);
            }

            return introspectionData;
        } catch (TokenIntrospectionException e) {
            // Re-throw TokenIntrospectionException
            throw e;
        } catch (Exception e) {
            log.error("Token introspection failed for client ID: {}", clientId, e);
            throw new TokenIntrospectionException("Failed to introspect token", e, clientId);
        }
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            String clientId = jwt.getClaimAsString(CLAIM_AZP);
            log.debug("Converting JWT to authentication token:{}", clientId);
            // Perform token introspection
            JwtToken introspectionData = performTokenIntrospection(jwt.getTokenValue(), clientId);

            // Extract authorities from the introspection data
            Collection<GrantedAuthority> authorities = extractAuthoritiesFromIntrospection(introspectionData);

            // Extract clientId from introspection data
            String tokenClientId = extractClientIdFromIntrospection(introspectionData);

            // Extract subject from introspection data
            String subject = introspectionData.getSub();
            if (subject == null || subject.isEmpty()) {
                subject = jwt.getSubject(); // Fallback to JWT subject if not in introspection data
            }

            // Extract the organisation the token was issued for
            String organisation = extractOrganisationFromIntrospection(introspectionData, jwt);

            // Create custom principal with subject, clientId and organisation
            EnhancedPrincipal principal = new EnhancedPrincipal(subject, tokenClientId, organisation);

            log.debug("Successfully created authentication token for client ID: {}", tokenClientId);

            // Return custom authentication token
            return new CustomJwtAuthenticationToken(jwt, authorities, principal);
        } catch (TokenIntrospectionException e) {
            // If introspection fails, log the error and fall back to JWT parsing
            String tokenClientId = extractClientId(jwt);
            log.warn(
                    "Token introspection failed for client ID: {}. Falling back to JWT parsing. Error: {}",
                    tokenClientId,
                    e.getMessage());

            Collection<GrantedAuthority> authorities = extractAuthorities(jwt);
            EnhancedPrincipal principal =
                    new EnhancedPrincipal(jwt.getSubject(), tokenClientId, extractOrganisation(jwt));
            return new CustomJwtAuthenticationToken(jwt, authorities, principal);
        } catch (ResourceAccessParsingException e) {
            // If resource access parsing fails, log the error and fall back to JWT parsing
            String tokenClientId = extractClientId(jwt);
            log.warn(
                    "Resource access parsing failed for client ID: {}. Falling back to JWT parsing. Error: {}",
                    tokenClientId,
                    e.getMessage());

            Collection<GrantedAuthority> authorities = extractAuthorities(jwt);
            EnhancedPrincipal principal =
                    new EnhancedPrincipal(jwt.getSubject(), tokenClientId, extractOrganisation(jwt));
            return new CustomJwtAuthenticationToken(jwt, authorities, principal);
        } catch (Exception e) {
            // For any other unexpected exceptions
            String tokenClientId = extractClientId(jwt);
            log.error("Unexpected error during JWT conversion for client ID: {}", tokenClientId, e);

            Collection<GrantedAuthority> authorities = extractAuthorities(jwt);
            EnhancedPrincipal principal =
                    new EnhancedPrincipal(jwt.getSubject(), tokenClientId, extractOrganisation(jwt));
            return new CustomJwtAuthenticationToken(jwt, authorities, principal);
        }
    }

    /**
     * Helper method to get a non-null, non-empty client ID from primary and fallback sources.
     *
     * @param primaryId  The primary client ID to check
     * @param fallbackId The fallback client ID to use if primary is null or empty
     * @return A non-null client ID (either primary, fallback, or UNKNOWN_CLIENT)
     */
    private String getEffectiveClientId(String primaryId, String fallbackId) {
        return getEffectiveValue(primaryId, fallbackId, UnknownIdentifiers.UNKNOWN_CLIENT);
    }

    /**
     * Returns the first of two candidate values that is neither null nor empty, or the
     * supplied default when neither is usable.
     *
     * @param primary      The preferred value
     * @param fallback     The value to use when primary is null or empty
     * @param defaultValue The value to use when neither candidate is usable
     * @return A non-null value
     */
    private String getEffectiveValue(String primary, String fallback, String defaultValue) {
        if (primary != null && !primary.isEmpty()) {
            return primary;
        }

        if (fallback != null && !fallback.isEmpty()) {
            return fallback;
        }

        return defaultValue;
    }

    /**
     * Extract the organisation from the JWT's "organisation" claim.
     * Returns {@code UNKNOWN_ORG} when the claim is absent or empty, so the principal
     * always carries a usable value.
     */
    private String extractOrganisation(Jwt jwt) {
        return getEffectiveValue(jwt.getClaimAsString(CLAIM_ORGANISATION), null, UnknownIdentifiers.UNKNOWN_ORG);
    }

    /**
     * Extract the organisation from introspection data, falling back to the JWT's own
     * "organisation" claim when introspection does not carry one - introspection is the more
     * authoritative source, but an older authorisation server may not echo the claim back.
     *
     * @param jwtToken The data from the introspection endpoint
     * @param jwt      The JWT the introspection was performed for
     * @return The organisation, or {@code UNKNOWN_ORG} when neither source has one
     */
    private String extractOrganisationFromIntrospection(JwtToken jwtToken, Jwt jwt) {
        return getEffectiveValue(
                jwtToken.getOrganisation(), jwt.getClaimAsString(CLAIM_ORGANISATION), UnknownIdentifiers.UNKNOWN_ORG);
    }

    /**
     * Extract client_id from JWT token.
     * Tries to get it from "azp" claim first, then from "client_id" claim.
     * If neither is present, returns {@code UNKNOWN_CLIENT}.
     */
    private String extractClientId(Jwt jwt) {
        String azpClientId = jwt.getClaimAsString(CLAIM_AZP);
        String directClientId = jwt.getClaimAsString(CLAIM_CLIENT_ID);

        return getEffectiveClientId(azpClientId, directClientId);
    }

    /**
     * Extract authorities from introspection data.
     *
     * @param jwtToken The data from the introspection endpoint
     * @return Collection of GrantedAuthority objects
     */
    private Collection<GrantedAuthority> extractAuthoritiesFromIntrospection(JwtToken jwtToken) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        String extractedClientId = extractClientIdFromIntrospection(jwtToken);

        try {
            log.debug("Extracting authorities from introspection data for client ID: {}", extractedClientId);

            // Process resource_access
            if (jwtToken.getResourceAccess() != null) {
                jwtToken.getResourceAccess().forEach((resource, resourceAccess) -> {
                    if (resourceAccess != null && resourceAccess.getRoles() != null) {
                        resourceAccess
                                .getRoles()
                                .forEach(role -> authorities.add(new SimpleGrantedAuthority(
                                        ROLE_PREFIX + resource + RESOURCE_ROLE_SEPARATOR + role)));
                    }
                });
            }

            log.trace("Successfully extracted {} authorities for client ID: {}", authorities.size(), extractedClientId);
        } catch (Exception e) {
            log.error("Error extracting authorities from introspection data for client ID: {}", extractedClientId, e);
            throw new ResourceAccessParsingException(
                    "Failed to parse resource access from introspection data", e, extractedClientId);
        }

        return authorities;
    }

    /**
     * Extract client_id from introspection data.
     * Tries to get it from "azp" claim first, then from "client_id" claim.
     * If neither is present, returns {@code UNKNOWN_CLIENT}.
     *
     * @param jwtToken The data from the introspection endpoint
     * @return The client ID
     */
    private String extractClientIdFromIntrospection(JwtToken jwtToken) {
        String azpClientId = jwtToken.getAzp();
        String directClientId = jwtToken.getClientId();

        return getEffectiveClientId(azpClientId, directClientId);
    }

    /**
     * Safely extracts a Map from an Object, if the object is a Map.
     *
     * @param obj The object to extract a Map from
     * @return An Optional containing the Map if extraction was successful, or empty Optional otherwise
     */
    @SuppressWarnings("unchecked") // Safe cast with instanceof check
    private Optional<Map<String, Object>> extractMap(Object obj) {
        if (obj instanceof Map) {
            return Optional.of((Map<String, Object>) obj);
        }
        return Optional.empty();
    }

    /**
     * Safely extracts a Collection of Strings from an Object, if the object is a Collection.
     *
     * @param obj The object to extract a Collection from
     * @return An Optional containing the Collection if extraction was successful, or empty Optional otherwise
     */
    @SuppressWarnings("unchecked") // Safe cast with instanceof check
    private Optional<Collection<String>> extractStringCollection(Object obj) {
        if (obj instanceof Collection) {
            return Optional.of((Collection<String>) obj);
        }
        return Optional.empty();
    }

    /**
     * Processes resource roles and creates authorities.
     *
     * @param resourceName The name of the resource
     * @param resourceData The resource data containing roles
     * @return A collection of GrantedAuthority objects
     */
    private Collection<GrantedAuthority> processResourceRoles(String resourceName, Map<String, Object> resourceData) {
        if (!resourceData.containsKey(CLAIM_ROLES)) {
            return Collections.emptyList();
        }

        return extractStringCollection(resourceData.get(CLAIM_ROLES))
                .map(roles -> roles.stream()
                        .map(role -> {
                            if (resourceName != null) {
                                // Resource-specific role (format: ROLE_<resource>:<role>)
                                return new SimpleGrantedAuthority(
                                        ROLE_PREFIX + resourceName + RESOURCE_ROLE_SEPARATOR + role);
                            } else {
                                // Realm role (format: ROLE_<role>)
                                return new SimpleGrantedAuthority(ROLE_PREFIX + role);
                            }
                        })
                        .<GrantedAuthority>map(authority -> authority)
                        .toList())
                .orElse(Collections.emptyList());
    }

    private Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        // Add default authorities if any
        Collection<GrantedAuthority> authorities = new ArrayList<>(defaultGrantedAuthoritiesConverter.convert(jwt));
        String extractClientId = extractClientId(jwt);

        try {
            log.trace("Extracting authorities from JWT for client ID: {}", extractClientId);

            // Extract and process resource_access claim
            extractMap(jwt.getClaim(CLAIM_RESOURCE_ACCESS))
                    .ifPresent(resourceAccess ->
                            resourceAccess.forEach((resource, resourceDataObj) -> extractMap(resourceDataObj)
                                    .ifPresent(resourceData ->
                                            authorities.addAll(processResourceRoles(resource, resourceData)))));

            log.trace(
                    "Successfully extracted {} authorities from JWT for client ID: {}",
                    authorities.size(),
                    extractClientId);
        } catch (Exception e) {
            log.error("Error extracting authorities from JWT for client ID: {}", extractClientId, e);
            // We're not throwing the exception here because we want to continue with default authorities
            // This is a fallback method, so we want to be more lenient
        }

        return authorities;
    }
}
