/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import uk.gov.dbt.ndtp.ia.node.management.model.UnknownIdentifiers;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.JwtToken;

/**
 * Covers the {@code organisation} claim reaching {@link EnhancedPrincipal}, from both the
 * introspection response and the JWT itself. Claim values are those of a real FEDERATOR_ENV
 * token, where {@code organisation} and {@code azp} happen to agree - the assertions here
 * deliberately do not rely on that, so a token whose organisation differs from its client id
 * would still be read correctly.
 */
@ExtendWith(MockitoExtension.class)
class KeycloakJwtAuthenticationConverterOrganisationTest {

    private static final String ORGANISATION = "FEDERATOR_ENV";
    private static final String UNKNOWN_ORGANISATION = UnknownIdentifiers.UNKNOWN_ORG;
    private static final String SUBJECT = "d17e51cf-ef3c-4adf-b51a-34a5f8b6c4f7";

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private KeycloakJwtAuthenticationConverter converter;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(
                converter,
                "introspectionUri",
                "https://localhost:8443/realms/mng-node/protocol/openid-connect/token/introspect");
        ReflectionTestUtils.setField(converter, "restTemplate", restTemplate);
    }

    private Jwt jwtWith(String organisation) {
        Map<String, Object> headers = Map.of("alg", "RS256", "typ", "JWT");

        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "https://localhost:8443/realms/mng-node");
        claims.put("aud", "management-node");
        claims.put("sub", SUBJECT);
        claims.put("typ", "Bearer");
        claims.put("azp", "FEDERATOR_ENV");
        claims.put("scope", "FEDERATOR_PRODUCER MANAGEMENT_NODE_ACCESS FEDERATOR_CONSUMER");
        claims.put(
                "resource_access",
                Map.of("management-node", Map.of("roles", List.of("access_producer_configurations", "create_keys"))));
        if (organisation != null) {
            claims.put("organisation", organisation);
        }

        return new Jwt(
                "token-value", Instant.ofEpochSecond(1789118956), Instant.ofEpochSecond(1789120757), headers, claims);
    }

    private void stubIntrospection(String organisation) {
        JwtToken introspection = JwtToken.builder()
                .active(true)
                .sub(SUBJECT)
                .azp("FEDERATOR_ENV")
                .organisation(organisation)
                .resourceAccess(Map.of(
                        "management-node",
                        JwtToken.ResourceAccess.builder()
                                .roles(List.of("access_producer_configurations"))
                                .build()))
                .build();

        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), org.mockito.Mockito.eq(JwtToken.class)))
                .thenReturn(new ResponseEntity<>(introspection, HttpStatus.OK));
    }

    private String organisationOf(AbstractAuthenticationToken token) {
        return ((EnhancedPrincipal) token.getPrincipal()).organisation();
    }

    @Test
    void convert_shouldTakeOrganisationFromIntrospection() {
        stubIntrospection(ORGANISATION);

        assertEquals(ORGANISATION, organisationOf(converter.convert(jwtWith(ORGANISATION))));
    }

    @Test
    void convert_shouldFallBackToJwtClaimWhenIntrospectionHasNoOrganisation() {
        stubIntrospection(null);

        assertEquals(ORGANISATION, organisationOf(converter.convert(jwtWith(ORGANISATION))));
    }

    @Test
    void convert_shouldUseUnknownOrganisationWhenNeitherSourceHasOne() {
        stubIntrospection(null);

        assertEquals(UNKNOWN_ORGANISATION, organisationOf(converter.convert(jwtWith(null))));
    }

    @Test
    void convert_shouldReadOrganisationFromJwtWhenIntrospectionFails() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), org.mockito.Mockito.eq(JwtToken.class)))
                .thenThrow(new RestClientException("introspection endpoint unavailable"));

        assertEquals(ORGANISATION, organisationOf(converter.convert(jwtWith(ORGANISATION))));
    }

    @Test
    void convert_shouldUseUnknownOrganisationWhenIntrospectionFailsAndClaimIsAbsent() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), org.mockito.Mockito.eq(JwtToken.class)))
                .thenThrow(new RestClientException("introspection endpoint unavailable"));

        assertEquals(UNKNOWN_ORGANISATION, organisationOf(converter.convert(jwtWith(null))));
    }

    @Test
    void convert_shouldTreatEmptyOrganisationAsUnknown() {
        stubIntrospection("");

        assertEquals(UNKNOWN_ORGANISATION, organisationOf(converter.convert(jwtWith(""))));
    }
}
