/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;

/**
 * Tests specifically for exception handling in KeycloakJwtAuthenticationConverter.
 */
@ExtendWith(MockitoExtension.class)
class KeycloakJwtAuthenticationConverterExceptionTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private KeycloakJwtAuthenticationConverter converter;

    private Jwt mockJwt;

    @BeforeEach
    void setUp() {
        // Set up configuration properties
        ReflectionTestUtils.setField(
                converter,
                "introspectionUri",
                "http://localhost:8080/realms/management-node/protocol/openid-connect/token/introspect");

        // Create a mock JWT with the sample token data
        Map<String, Object> headers = new HashMap<>();
        headers.put("alg", "RS256");
        headers.put("typ", "JWT");

        Map<String, Object> claims = new HashMap<>();
        claims.put("exp", 1753576065);
        claims.put("iat", 1753575765);
        claims.put("jti", "trrtcc:713a03f4-55fb-4198-e8ea-a1be37d5f52f");
        claims.put("iss", "http://localhost:8080/realms/management-node");
        claims.put("sub", "86a41a8a-ab2e-465e-8b48-a09d3275f842");
        claims.put("typ", "Bearer");
        claims.put("azp", "management-node");
        claims.put("client_id", "management-node");

        // Set up the aud claim as a list
        List<String> audiences = Arrays.asList("F1", "F2");
        claims.put("aud", audiences);

        // Set up resource_access claim with nested roles
        Map<String, Object> resourceAccess = new HashMap<>();
        Map<String, Object> f1Resource = new HashMap<>();
        List<String> f1Roles = Arrays.asList("TOPIC_2", "TOPIC_1");
        f1Resource.put("roles", f1Roles);
        resourceAccess.put("F1", f1Resource);
        claims.put("resource_access", resourceAccess);

        // Create the JWT with the headers and claims
        mockJwt = new Jwt(
                "token-value", Instant.ofEpochSecond(1753575765), Instant.ofEpochSecond(1753576065), headers, claims);

        // Inject mock RestTemplate
        ReflectionTestUtils.setField(converter, "restTemplate", restTemplate);
    }

    @Test
    void convert_withRestClientException_shouldFallbackToJwtParsing() {
        // Arrange
        // Configure RestTemplate to throw a RestClientException
        when(restTemplate.postForEntity(
                        anyString(),
                        any(HttpEntity.class),
                        Mockito.<Class<uk.gov.dbt.ndtp.ia.node.management.model.jwt.JwtToken>>any()))
                .thenThrow(new RestClientException("Connection refused"));

        // Act
        AbstractAuthenticationToken token = converter.convert(mockJwt);

        // Assert
        assertNotNull(token);
        assertTrue(token instanceof CustomJwtAuthenticationToken);

        // Verify the token has the correct principal
        EnhancedPrincipal principal = ((CustomJwtAuthenticationToken) token).getPrincipal();
        assertEquals("86a41a8a-ab2e-465e-8b48-a09d3275f842", principal.subject());
        assertEquals("management-node", principal.clientId());
    }

    @Test
    void convert_withInactiveToken_shouldFallbackToJwtParsing() {
        // Arrange
        // Create an introspection response with inactive token
        uk.gov.dbt.ndtp.ia.node.management.model.jwt.JwtToken inactiveTokenResponse =
                new uk.gov.dbt.ndtp.ia.node.management.model.jwt.JwtToken();
        inactiveTokenResponse.setActive(false);

        // Configure RestTemplate to return the inactive token response
        ResponseEntity<uk.gov.dbt.ndtp.ia.node.management.model.jwt.JwtToken> responseEntity =
                new ResponseEntity<>(inactiveTokenResponse, HttpStatus.OK);
        when(restTemplate.postForEntity(
                        anyString(),
                        any(HttpEntity.class),
                        Mockito.<Class<uk.gov.dbt.ndtp.ia.node.management.model.jwt.JwtToken>>any()))
                .thenReturn(responseEntity);

        // Act
        AbstractAuthenticationToken token = converter.convert(mockJwt);

        // Assert
        assertNotNull(token);
        assertTrue(token instanceof CustomJwtAuthenticationToken);

        // Verify the token has the correct principal
        EnhancedPrincipal principal = ((CustomJwtAuthenticationToken) token).getPrincipal();
        assertEquals("86a41a8a-ab2e-465e-8b48-a09d3275f842", principal.subject());
        assertEquals("management-node", principal.clientId());
    }

    @Test
    void convert_withNullIntrospectionResponse_shouldFallbackToJwtParsing() {
        // Arrange
        // Configure RestTemplate to return null response body
        ResponseEntity<uk.gov.dbt.ndtp.ia.node.management.model.jwt.JwtToken> responseEntity =
                new ResponseEntity<>(null, HttpStatus.OK);
        when(restTemplate.postForEntity(
                        anyString(),
                        any(HttpEntity.class),
                        Mockito.<Class<uk.gov.dbt.ndtp.ia.node.management.model.jwt.JwtToken>>any()))
                .thenReturn(responseEntity);

        // Act
        AbstractAuthenticationToken token = converter.convert(mockJwt);

        // Assert
        assertNotNull(token);
        assertTrue(token instanceof CustomJwtAuthenticationToken);

        // Verify the token has the correct principal
        EnhancedPrincipal principal = ((CustomJwtAuthenticationToken) token).getPrincipal();
        assertEquals("86a41a8a-ab2e-465e-8b48-a09d3275f842", principal.subject());
        assertEquals("management-node", principal.clientId());
    }
}
