/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import uk.gov.dbt.ndtp.ia.node.management.exception.TokenIntrospectionException;
import uk.gov.dbt.ndtp.ia.node.management.model.UnknownIdentifiers;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.JwtToken;

@ExtendWith(MockitoExtension.class)
class KeycloakJwtAuthenticationConverterTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private KeycloakJwtAuthenticationConverter converter;

    private Jwt mockJwt;
    private Map<String, Object> mockIntrospectionResponse;

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

        // Set up the allowed-origins claim
        List<String> allowedOrigins = Collections.singletonList("/*");
        claims.put("allowed-origins", allowedOrigins);

        // Set up the resource_access claim with nested roles
        Map<String, Object> resourceAccess = new HashMap<>();

        // F1 resource
        Map<String, Object> f1Resource = new HashMap<>();
        List<String> f1Roles = Arrays.asList("TOPIC_2", "TOPIC_1");
        f1Resource.put("roles", f1Roles);
        resourceAccess.put("F1", f1Resource);

        // management-node resource
        Map<String, Object> managementNodeResource = new HashMap<>();
        List<String> managementNodeRoles = Collections.singletonList("MyRole");
        managementNodeResource.put("roles", managementNodeRoles);
        resourceAccess.put("management-node", managementNodeResource);

        // F2 resource
        Map<String, Object> f2Resource = new HashMap<>();
        List<String> f2Roles = Collections.singletonList("R1");
        f2Resource.put("roles", f2Roles);
        resourceAccess.put("F2", f2Resource);

        claims.put("resource_access", resourceAccess);

        // Additional claims
        claims.put("scope", "Sample_ORG management-node-client-scope");
        claims.put("clientHost", "172.20.0.1");
        claims.put("clientAddress", "172.20.0.1");

        // Create the JWT with the headers and claims
        mockJwt = new Jwt(
                "token-value", Instant.ofEpochSecond(1753575765), Instant.ofEpochSecond(1753576065), headers, claims);

        // Create mock introspection response
        mockIntrospectionResponse = new HashMap<>();
        mockIntrospectionResponse.put("active", true);
        mockIntrospectionResponse.put("exp", 1753576065);
        mockIntrospectionResponse.put("iat", 1753575765);
        mockIntrospectionResponse.put("jti", "trrtcc:713a03f4-55fb-4198-e8ea-a1be37d5f52f");
        mockIntrospectionResponse.put("iss", "http://localhost:8080/realms/management-node");
        mockIntrospectionResponse.put("sub", "86a41a8a-ab2e-465e-8b48-a09d3275f842");
        mockIntrospectionResponse.put("typ", "Bearer");
        mockIntrospectionResponse.put("azp", "management-node");
        mockIntrospectionResponse.put("client_id", "management-node");
        mockIntrospectionResponse.put("aud", audiences);
        mockIntrospectionResponse.put("allowed-origins", allowedOrigins);
        mockIntrospectionResponse.put("resource_access", resourceAccess);
        mockIntrospectionResponse.put("scope", "Sample_ORG management-node-client-scope");
        mockIntrospectionResponse.put("clientHost", "172.20.0.1");
        mockIntrospectionResponse.put("clientAddress", "172.20.0.1");

        // Inject mock RestTemplate
        ReflectionTestUtils.setField(converter, "restTemplate", restTemplate);
    }

    private void setupMockIntrospectionResponse(Map<String, Object> response) {
        // Convert Map to JwtToken
        JwtToken jwtToken = createJwtTokenFromMap(response);
        ResponseEntity<JwtToken> responseEntity = new ResponseEntity<>(jwtToken, HttpStatus.OK);
        Mockito.lenient()
                .when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), Mockito.eq(JwtToken.class)))
                .thenReturn(responseEntity);
    }

    private JwtToken createJwtTokenFromMap(Map<String, Object> map) {
        if (map == null) {
            return null;
        }

        // Extract resource_access and convert it to the format expected by JwtToken
        Map<String, Object> resourceAccessMap = (Map<String, Object>) map.get("resource_access");
        Map<String, JwtToken.ResourceAccess> resourceAccess = new HashMap<>();

        if (resourceAccessMap != null) {
            resourceAccessMap.forEach((resource, resourceDataObj) -> {
                if (resourceDataObj instanceof Map) {
                    Map<String, Object> resourceData = (Map<String, Object>) resourceDataObj;
                    List<String> roles = (List<String>) resourceData.get("roles");
                    if (roles != null) {
                        resourceAccess.put(resource, new JwtToken.ResourceAccess(roles));
                    }
                }
            });
        }

        // Extract other fields
        // Handle numeric values that could be Integer or Long
        Long exp = null;
        if (map.get("exp") != null) {
            exp = map.get("exp") instanceof Long ? (Long) map.get("exp") : ((Number) map.get("exp")).longValue();
        }

        Long iat = null;
        if (map.get("iat") != null) {
            iat = map.get("iat") instanceof Long ? (Long) map.get("iat") : ((Number) map.get("iat")).longValue();
        }

        return JwtToken.builder()
                .active((Boolean) map.get("active"))
                .exp(exp)
                .iat(iat)
                .jti((String) map.get("jti"))
                .iss((String) map.get("iss"))
                .sub((String) map.get("sub"))
                .typ((String) map.get("typ"))
                .azp((String) map.get("azp"))
                .clientId((String) map.get("client_id"))
                .aud((List<String>) map.get("aud"))
                .allowedOrigins((List<String>) map.get("allowed-origins"))
                .resourceAccess(resourceAccess)
                .scope((String) map.get("scope"))
                .organisation((String) map.get("organisation"))
                .username((String) map.get("username"))
                .tokenType((String) map.get("token_type"))
                .build();
    }

    @Test
    void convert_shouldExtractCorrectAuthorities() {
        // Setup mock introspection response
        setupMockIntrospectionResponse(mockIntrospectionResponse);

        // Act
        AbstractAuthenticationToken token = converter.convert(mockJwt);

        // Assert
        assertNotNull(token);
        assertTrue(token instanceof CustomJwtAuthenticationToken);
        assertEquals("86a41a8a-ab2e-465e-8b48-a09d3275f842", token.getName());

        // Verify the CustomPrincipal has the correct clientId
        EnhancedPrincipal principal = ((CustomJwtAuthenticationToken) token).getPrincipal();
        assertNotNull(principal);
        assertEquals("86a41a8a-ab2e-465e-8b48-a09d3275f842", principal.subject());
        assertEquals("management-node", principal.clientId());

        Collection<GrantedAuthority> authorities = token.getAuthorities();
        assertNotNull(authorities);

        // Verify the expected authorities are present
        List<String> expectedAuthorities =
                Arrays.asList("ROLE_F1:TOPIC_1", "ROLE_F1:TOPIC_2", "ROLE_F2:R1", "ROLE_management-node:MyRole");

        // Don't assert the exact count as JwtGrantedAuthoritiesConverter may add default authorities
        // Just verify that all our expected authorities are present

        for (String expectedAuthority : expectedAuthorities) {
            boolean found = authorities.stream()
                    .anyMatch(authority -> authority.getAuthority().equals(expectedAuthority));
            assertTrue(found, "Expected authority not found: " + expectedAuthority);
        }
    }

    @Test
    void convert_withNullResourceAccess_shouldNotFail() {
        // Arrange
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "test-subject");

        Jwt jwtWithoutResourceAccess = new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Collections.singletonMap("alg", "none"),
                claims);

        // Setup mock introspection response to return null (simulate introspection failure)
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), Mockito.eq(JwtToken.class)))
                .thenThrow(new RuntimeException("Simulated introspection failure"));

        // Act
        AbstractAuthenticationToken token = converter.convert(jwtWithoutResourceAccess);

        // Assert
        assertNotNull(token);
        assertTrue(token instanceof CustomJwtAuthenticationToken);
        assertEquals("test-subject", token.getName());

        // Verify the CustomPrincipal has the correct values
        EnhancedPrincipal principal = ((CustomJwtAuthenticationToken) token).getPrincipal();
        assertNotNull(principal);
        assertEquals("test-subject", principal.subject());
        assertEquals(UnknownIdentifiers.UNKNOWN_CLIENT, principal.clientId());

        // Should not throw exception and return token with default authorities
    }

    @Test
    void convert_withEmptyRoles_shouldNotAddAuthorities() {
        // Arrange
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "test-subject");

        Map<String, Object> resourceAccess = new HashMap<>();
        Map<String, Object> resource = new HashMap<>();
        resource.put("roles", Collections.emptyList());
        resourceAccess.put("test-resource", resource);
        claims.put("resource_access", resourceAccess);

        Jwt jwtWithEmptyRoles = new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Collections.singletonMap("alg", "none"),
                claims);

        // Setup mock introspection response to return null (simulate introspection failure)
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), Mockito.eq(JwtToken.class)))
                .thenThrow(new RuntimeException("Simulated introspection failure"));

        // Act
        AbstractAuthenticationToken token = converter.convert(jwtWithEmptyRoles);

        // Assert
        assertNotNull(token);
        assertTrue(token instanceof CustomJwtAuthenticationToken);

        // Verify the CustomPrincipal has the correct values
        EnhancedPrincipal principal = ((CustomJwtAuthenticationToken) token).getPrincipal();
        assertNotNull(principal);
        assertEquals("test-subject", principal.subject());
        assertEquals(UnknownIdentifiers.UNKNOWN_CLIENT, principal.clientId());

        // Should not add any authorities for the empty roles list
        assertEquals(0, token.getAuthorities().size());
    }

    @Test
    void convert_withMalformedResourceAccess_shouldHandleGracefully() {
        // Arrange
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "test-subject");

        // Malformed resource_access (not a map)
        claims.put("resource_access", "not-a-map");

        Jwt jwtWithMalformedResourceAccess = new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Collections.singletonMap("alg", "none"),
                claims);

        // Setup mock introspection response to return null (simulate introspection failure)
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), Mockito.eq(JwtToken.class)))
                .thenThrow(new RuntimeException("Simulated introspection failure"));

        // Act & Assert
        // Should not throw exception
        AbstractAuthenticationToken token = converter.convert(jwtWithMalformedResourceAccess);
        assertNotNull(token);
        assertTrue(token instanceof CustomJwtAuthenticationToken);

        // Verify the CustomPrincipal has the correct values
        EnhancedPrincipal principal = ((CustomJwtAuthenticationToken) token).getPrincipal();
        assertNotNull(principal);
        assertEquals("test-subject", principal.subject());
        assertEquals(UnknownIdentifiers.UNKNOWN_CLIENT, principal.clientId());
    }

    @Test
    void convert_shouldUseIntrospectionEndpoint() {
        // Setup mock introspection response
        setupMockIntrospectionResponse(mockIntrospectionResponse);

        // Act
        AbstractAuthenticationToken token = converter.convert(mockJwt);

        // Assert
        // Verify that the RestTemplate was called
        Mockito.verify(restTemplate).postForEntity(anyString(), any(HttpEntity.class), Mockito.<Class<Map>>any());

        // Verify the token is correct
        assertNotNull(token);
        assertTrue(token instanceof CustomJwtAuthenticationToken);
        assertEquals("86a41a8a-ab2e-465e-8b48-a09d3275f842", token.getName());

        // Verify the CustomPrincipal has the correct clientId
        EnhancedPrincipal principal = ((CustomJwtAuthenticationToken) token).getPrincipal();
        assertNotNull(principal);
        assertEquals("86a41a8a-ab2e-465e-8b48-a09d3275f842", principal.subject());
        assertEquals("management-node", principal.clientId());

        // Verify the authorities
        Collection<GrantedAuthority> authorities = token.getAuthorities();
        assertNotNull(authorities);

        // Verify the expected authorities are present
        List<String> expectedAuthorities =
                Arrays.asList("ROLE_F1:TOPIC_1", "ROLE_F1:TOPIC_2", "ROLE_F2:R1", "ROLE_management-node:MyRole");

        for (String expectedAuthority : expectedAuthorities) {
            boolean found = authorities.stream()
                    .anyMatch(authority -> authority.getAuthority().equals(expectedAuthority));
            assertTrue(found, "Expected authority not found: " + expectedAuthority);
        }
    }

    @Test
    void convert_withIntrospectionFailure_shouldFallbackToJwtParsing() {
        // Arrange
        // Configure RestTemplate to throw an exception
        Mockito.reset(restTemplate); // Reset any previous stubbing
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), Mockito.eq(JwtToken.class)))
                .thenThrow(new RuntimeException("Introspection failed"));

        // Act
        AbstractAuthenticationToken token = converter.convert(mockJwt);

        // Assert
        // Verify that the token is still created using JWT parsing
        assertNotNull(token);
        assertTrue(token instanceof CustomJwtAuthenticationToken);
        assertEquals("86a41a8a-ab2e-465e-8b48-a09d3275f842", token.getName());

        // Verify the CustomPrincipal has the correct clientId
        EnhancedPrincipal principal = ((CustomJwtAuthenticationToken) token).getPrincipal();
        assertNotNull(principal);
        assertEquals("86a41a8a-ab2e-465e-8b48-a09d3275f842", principal.subject());
        assertEquals("management-node", principal.clientId());
    }

    @Test
    void convert_withTokenNotActive_shouldFallbackToJwtParsing() {
        // Arrange
        Map<String, Object> response = new HashMap<>(mockIntrospectionResponse);
        response.put("active", false);
        setupMockIntrospectionResponse(response);

        // Act
        AbstractAuthenticationToken token = converter.convert(mockJwt);

        // Assert
        assertNotNull(token);
        assertTrue(token instanceof CustomJwtAuthenticationToken);
        assertEquals(
                "management-node",
                ((CustomJwtAuthenticationToken) token).getPrincipal().clientId());
    }

    @Test
    void convert_withNullIntrospectionBody_shouldFallbackToJwtParsing() {
        // Arrange
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), Mockito.eq(JwtToken.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        // Act
        AbstractAuthenticationToken token = converter.convert(mockJwt);

        // Assert
        assertNotNull(token);
        assertTrue(token instanceof CustomJwtAuthenticationToken);
    }

    @Test
    void convert_withIntrospectionException_shouldFallbackToJwtParsing() {
        // Arrange
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), Mockito.eq(JwtToken.class)))
                .thenThrow(new TokenIntrospectionException("Introspection failed", "test-client"));

        // Act
        AbstractAuthenticationToken token = converter.convert(mockJwt);

        // Assert
        assertNotNull(token);
    }

    @Test
    void extractClientId_variousScenarios() {
        // azp is missing, client_id is present
        Map<String, Object> claims1 = new HashMap<>();
        claims1.put("sub", "test-sub");
        claims1.put("client_id", "direct-client");
        Jwt jwt1 =
                new Jwt("v", Instant.now(), Instant.now().plusSeconds(30), Collections.singletonMap("a", "b"), claims1);

        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), Mockito.eq(JwtToken.class)))
                .thenThrow(new RuntimeException());

        AbstractAuthenticationToken token1 = converter.convert(jwt1);
        assertEquals(
                "direct-client",
                ((CustomJwtAuthenticationToken) token1).getPrincipal().clientId());

        // azp is empty, client_id is present
        Map<String, Object> claims2 = new HashMap<>();
        claims2.put("sub", "test-sub");
        claims2.put("azp", "");
        claims2.put("client_id", "direct-client");
        Jwt jwt2 =
                new Jwt("v", Instant.now(), Instant.now().plusSeconds(30), Collections.singletonMap("a", "b"), claims2);
        AbstractAuthenticationToken token2 = converter.convert(jwt2);
        assertEquals(
                "direct-client",
                ((CustomJwtAuthenticationToken) token2).getPrincipal().clientId());

        // azp is null, client_id is missing
        Map<String, Object> claims3 = new HashMap<>();
        claims3.put("sub", "test-sub");
        Jwt jwt3 =
                new Jwt("v", Instant.now(), Instant.now().plusSeconds(30), Collections.singletonMap("a", "b"), claims3);
        AbstractAuthenticationToken token3 = converter.convert(jwt3);
        assertEquals(
                UnknownIdentifiers.UNKNOWN_CLIENT,
                ((CustomJwtAuthenticationToken) token3).getPrincipal().clientId());
    }

    @Test
    void extractAuthorities_withVariousData() {
        // Mock fallback path
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), Mockito.eq(JwtToken.class)))
                .thenThrow(new RuntimeException());

        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "test-sub");

        Map<String, Object> resourceAccess = new HashMap<>();
        // Resource with no roles field
        resourceAccess.put("no-roles", new HashMap<String, Object>());
        // Resource with roles that is not a collection
        Map<String, Object> invalidRoles = new HashMap<>();
        invalidRoles.put("roles", "not-a-collection");
        resourceAccess.put("invalid-roles", invalidRoles);

        claims.put("resource_access", resourceAccess);

        Jwt jwt =
                new Jwt("v", Instant.now(), Instant.now().plusSeconds(30), Collections.singletonMap("a", "b"), claims);
        AbstractAuthenticationToken token = converter.convert(jwt);

        assertNotNull(token);
        // Should have 0 authorities besides default ones (none in this case)
        assertEquals(0, token.getAuthorities().size());
    }

    @Test
    void extractAuthoritiesFromIntrospection_withException_shouldThrowResourceAccessParsingException() {
        // Arrange
        JwtToken jwtToken = Mockito.mock(JwtToken.class);
        when(jwtToken.getAzp()).thenReturn("client-id");
        when(jwtToken.getResourceAccess()).thenThrow(new RuntimeException("Simulated error"));

        // Act & Assert
        assertThrows(
                uk.gov.dbt.ndtp.ia.node.management.exception.ResourceAccessParsingException.class,
                () -> ReflectionTestUtils.invokeMethod(converter, "extractAuthoritiesFromIntrospection", jwtToken));
    }

    @Test
    void extractAuthorities_withException_shouldLogAndReturnDefaultAuthorities() {
        // Arrange
        Jwt jwt = Mockito.mock(Jwt.class);
        when(jwt.getClaimAsString(anyString())).thenReturn("client-id");
        when(jwt.getClaim(anyString())).thenThrow(new RuntimeException("Simulated error"));

        // Act
        Collection<org.springframework.security.core.GrantedAuthority> authorities =
                ReflectionTestUtils.invokeMethod(converter, "extractAuthorities", jwt);

        // Assert
        assertNotNull(authorities);
    }

    @Test
    void processResourceRoles_realmRoles() {
        // Test processResourceRoles with resourceName = null (Realm roles)
        Map<String, Object> resourceData = new HashMap<>();
        resourceData.put("roles", List.of("role1", "role2"));

        Collection<org.springframework.security.core.GrantedAuthority> authorities =
                ReflectionTestUtils.invokeMethod(converter, "processResourceRoles", null, resourceData);

        assertNotNull(authorities);
        assertEquals(2, authorities.size());
        assertTrue(authorities.stream().anyMatch(a -> a.getAuthority().equals("ROLE_role1")));
        assertTrue(authorities.stream().anyMatch(a -> a.getAuthority().equals("ROLE_role2")));
    }

    @Test
    void convert_withGeneralException_shouldFallbackToJwtParsing() {
        // Arrange
        // This will cause a NullPointerException in performTokenIntrospection or convert
        Mockito.reset(restTemplate);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), Mockito.eq(JwtToken.class)))
                .thenReturn(null);

        // Act
        AbstractAuthenticationToken token = converter.convert(mockJwt);

        // Assert
        assertNotNull(token);
    }

    @Test
    void convert_introspectionNoSubject_shouldFallbackToJwtSubject() {
        // Arrange
        Map<String, Object> response = new HashMap<>(mockIntrospectionResponse);
        response.remove("sub");
        setupMockIntrospectionResponse(response);

        // Act
        AbstractAuthenticationToken token = converter.convert(mockJwt);

        // Assert
        assertEquals("86a41a8a-ab2e-465e-8b48-a09d3275f842", token.getName());
    }
}
