/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import uk.gov.dbt.ndtp.ia.node.management.web.security.ClientIdMdcFilter;
import uk.gov.dbt.ndtp.ia.node.management.web.security.KeycloakJwtAuthenticationConverter;

@ExtendWith(MockitoExtension.class)
class SecurityConfigTest {

    @Mock
    private KeycloakJwtAuthenticationConverter keycloakJwtAuthenticationConverter;

    @Mock
    private ClientIdMdcFilter clientIdMdcFilter;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private HttpSecurity httpSecurity;

    private SecurityConfig securityConfig;

    @BeforeEach
    void setUp() {
        securityConfig = new SecurityConfig(keycloakJwtAuthenticationConverter, clientIdMdcFilter);
    }

    @Test
    void securityFilterChain_shouldConfigureSecurity() throws Exception {
        // Arrange
        SecurityFilterChain mockFilterChain = mock(SecurityFilterChain.class);
        doReturn(httpSecurity).when(httpSecurity).csrf(any());
        doReturn(httpSecurity).when(httpSecurity).authorizeHttpRequests(any());
        doReturn(httpSecurity).when(httpSecurity).oauth2ResourceServer(any());
        doReturn(httpSecurity).when(httpSecurity).sessionManagement(any());
        doReturn(httpSecurity).when(httpSecurity).addFilterAfter(any(), any());
        doReturn(mockFilterChain).when(httpSecurity).build();

        // Act
        SecurityFilterChain result = securityConfig.securityFilterChain(httpSecurity);

        // Assert
        assertNotNull(result);
        assertEquals(mockFilterChain, result);
    }
}
