/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;

class ClientIdMdcFilterTest {

    private ClientIdMdcFilter filter;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Authentication authentication;

    private AutoCloseable closeable;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        filter = new ClientIdMdcFilter();
        SecurityContextHolder.setContext(securityContext);
        MDC.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        SecurityContextHolder.clearContext();
        MDC.clear();
        closeable.close();
    }

    @Test
    void doFilterInternal_withEnhancedPrincipal_shouldSetMdc() throws ServletException, IOException {
        // Arrange
        EnhancedPrincipal principal = new EnhancedPrincipal("subject", "test-client-id", "test-organisation");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(authentication.getName()).thenReturn("test-user");

        // Act
        filter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        assertNull(MDC.get(ClientIdMdcFilter.CLIENT_ID_MDC_KEY)); // MDC should be cleared in finally block
    }

    @Test
    void doFilterInternal_withNullAuthentication_shouldSetEmptyMdc() throws ServletException, IOException {
        // Arrange
        when(securityContext.getAuthentication()).thenReturn(null);

        // Act
        filter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        assertNull(MDC.get(ClientIdMdcFilter.CLIENT_ID_MDC_KEY));
    }

    @Test
    void doFilterInternal_withNonEnhancedPrincipal_shouldSetEmptyMdc() throws ServletException, IOException {
        // Arrange
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn("not-an-enhanced-principal");

        // Act
        filter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        assertNull(MDC.get(ClientIdMdcFilter.CLIENT_ID_MDC_KEY));
    }

    @Test
    void doFilterInternal_withEnhancedPrincipalButEmptyClientId_shouldSetEmptyMdc()
            throws ServletException, IOException {
        // Arrange
        EnhancedPrincipal principal = new EnhancedPrincipal("subject", "", "test-organisation");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(principal);

        // Act
        filter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        assertNull(MDC.get(ClientIdMdcFilter.CLIENT_ID_MDC_KEY));
    }

    @Test
    void doFilterInternal_withEnhancedPrincipalButNullClientId_shouldSetEmptyMdc()
            throws ServletException, IOException {
        // Arrange
        EnhancedPrincipal principal = new EnhancedPrincipal("subject", null, "test-organisation");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(principal);

        // Act
        filter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        assertNull(MDC.get(ClientIdMdcFilter.CLIENT_ID_MDC_KEY));
    }

    @Test
    void doFilterInternal_withNullPrincipal_shouldSetEmptyMdc() throws ServletException, IOException {
        // Arrange
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(null);

        // Act
        filter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(filterChain).doFilter(request, response);
        assertNull(MDC.get(ClientIdMdcFilter.CLIENT_ID_MDC_KEY));
    }

    @Test
    void doFilterInternal_shouldClearMdcEvenOnException() throws ServletException, IOException {
        // Arrange
        EnhancedPrincipal principal = new EnhancedPrincipal("subject", "test-client-id", "test-organisation");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(principal);
        doThrow(new RuntimeException("Test exception")).when(filterChain).doFilter(request, response);

        // Act & Assert
        try {
            filter.doFilterInternal(request, response, filterChain);
        } catch (RuntimeException e) {
            assertEquals("Test exception", e.getMessage());
        }

        assertNull(MDC.get(ClientIdMdcFilter.CLIENT_ID_MDC_KEY));
    }
}
