/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OrganisationCertificateDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateType;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.CertificateValidationProvider;

class CertificateValidationInterceptorTest {

    @Mock
    private CertificateValidationProvider validationProvider;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private HandlerMethod handlerMethod;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Authentication authentication;

    private CertificateValidationInterceptor interceptor;
    private AutoCloseable closeable;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        interceptor = new CertificateValidationInterceptor(validationProvider, new ObjectMapper());
        SecurityContextHolder.setContext(securityContext);
    }

    @AfterEach
    void tearDown() throws Exception {
        SecurityContextHolder.clearContext();
        closeable.close();
    }

    private void setupAuthentication(String clientId) {
        EnhancedPrincipal principal = new EnhancedPrincipal("subject", clientId, "test-organisation");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(principal);
    }

    private StringWriter setupResponseWriter() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        when(response.getWriter()).thenReturn(pw);
        return sw;
    }

    private OrganisationCertificateDTO certDto(CertificateType type, String serial) {
        return OrganisationCertificateDTO.builder()
                .id(1L)
                .organisationId(1L)
                .certificateAutomationEnabled(type != CertificateType.MANUAL)
                .type(type)
                .serialNumber(serial)
                .expiresAt(Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)))
                .build();
    }

    @Test
    void noAuthentication_returns403() throws Exception {
        when(securityContext.getAuthentication()).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/configuration/consumer");
        setupResponseWriter();

        assertThat(interceptor.preHandle(request, response, handlerMethod)).isFalse();
        verify(response).setStatus(403);
    }

    @Test
    void nonEnhancedPrincipal_returns403() throws Exception {
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn("plain-string-principal");
        when(request.getRequestURI()).thenReturn("/api/v1/configuration/consumer");
        setupResponseWriter();

        assertThat(interceptor.preHandle(request, response, handlerMethod)).isFalse();
        verify(response).setStatus(403);
    }

    @Test
    void emptyClientId_returns403() throws Exception {
        setupAuthentication("");
        when(request.getRequestURI()).thenReturn("/api/v1/configuration/consumer");
        setupResponseWriter();

        assertThat(interceptor.preHandle(request, response, handlerMethod)).isFalse();
        verify(response).setStatus(403);
    }

    @Test
    void noCertRecord_returns403() throws Exception {
        setupAuthentication("client-1");
        when(validationProvider.findByClientId("client-1")).thenReturn(Optional.empty());
        when(request.getRequestURI()).thenReturn("/api/v1/configuration/consumer");
        setupResponseWriter();

        assertThat(interceptor.preHandle(request, response, handlerMethod)).isFalse();
        verify(response).setStatus(403);
    }

    @ParameterizedTest
    @EnumSource(
            value = CertificateType.class,
            names = {"AUTOMATED", "MANUAL"})
    void activeCert_passesThrough(CertificateType type) throws Exception {
        setupAuthentication("client-1");
        OrganisationCertificateDTO cert = certDto(type, null);
        when(validationProvider.findByClientId("client-1")).thenReturn(Optional.of(cert));
        when(validationProvider.isActive(cert)).thenReturn(true);

        assertThat(interceptor.preHandle(request, response, handlerMethod)).isTrue();
    }

    @Test
    void inactiveCert_returns403() throws Exception {
        setupAuthentication("client-1");
        OrganisationCertificateDTO cert = certDto(CertificateType.AUTOMATED, null);
        when(validationProvider.findByClientId("client-1")).thenReturn(Optional.of(cert));
        when(validationProvider.isActive(cert)).thenReturn(false);
        when(request.getRequestURI()).thenReturn("/api/v1/configuration/consumer");
        setupResponseWriter();

        assertThat(interceptor.preHandle(request, response, handlerMethod)).isFalse();
        verify(response).setStatus(403);
    }

    static Stream<Arguments> serialNumberFormats() {
        return Stream.of(
                Arguments.of("abc123", "plain hex match"),
                Arguments.of("ABC123", "case-insensitive match"),
                Arguments.of("ab:c1:23", "colon-separated match"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("serialNumberFormats")
    void matchingSerialNumber_passesThrough(String storedSerial, String description) throws Exception {
        setupAuthentication("client-1");
        OrganisationCertificateDTO cert = certDto(CertificateType.AUTOMATED, storedSerial);
        when(validationProvider.findByClientId("client-1")).thenReturn(Optional.of(cert));
        when(validationProvider.isActive(cert)).thenReturn(true);

        X509Certificate mockCert = mock(X509Certificate.class);
        when(mockCert.getSerialNumber()).thenReturn(new BigInteger("abc123", 16));
        when(request.getAttribute("jakarta.servlet.request.X509Certificate"))
                .thenReturn(new X509Certificate[] {mockCert});

        assertThat(interceptor.preHandle(request, response, handlerMethod)).isTrue();
    }

    @Test
    void mismatchedSerialNumber_returns403() throws Exception {
        setupAuthentication("client-1");
        OrganisationCertificateDTO cert = certDto(CertificateType.AUTOMATED, "abc123");
        when(validationProvider.findByClientId("client-1")).thenReturn(Optional.of(cert));
        when(validationProvider.isActive(cert)).thenReturn(true);

        X509Certificate mockCert = mock(X509Certificate.class);
        when(mockCert.getSerialNumber()).thenReturn(new BigInteger("def456", 16));
        when(request.getAttribute("jakarta.servlet.request.X509Certificate"))
                .thenReturn(new X509Certificate[] {mockCert});
        setupResponseWriter();

        assertThat(interceptor.preHandle(request, response, handlerMethod)).isFalse();
        verify(response).setStatus(403);
    }

    @Test
    void noCertificateWhenSerialExpected_returns403() throws Exception {
        setupAuthentication("client-1");
        OrganisationCertificateDTO cert = certDto(CertificateType.AUTOMATED, "abc123");
        when(validationProvider.findByClientId("client-1")).thenReturn(Optional.of(cert));
        when(validationProvider.isActive(cert)).thenReturn(true);
        when(request.getAttribute("jakarta.servlet.request.X509Certificate")).thenReturn(null);
        setupResponseWriter();

        assertThat(interceptor.preHandle(request, response, handlerMethod)).isFalse();
        verify(response).setStatus(403);
    }

    @Test
    void nullSerialNumber_skipsCheck() throws Exception {
        setupAuthentication("client-1");
        OrganisationCertificateDTO cert = certDto(CertificateType.AUTOMATED, null);
        when(validationProvider.findByClientId("client-1")).thenReturn(Optional.of(cert));
        when(validationProvider.isActive(cert)).thenReturn(true);

        assertThat(interceptor.preHandle(request, response, handlerMethod)).isTrue();
        verify(request, never()).getAttribute("jakarta.servlet.request.X509Certificate");
    }

    @Test
    void bootstrapCert_alwaysReturns403() throws Exception {
        setupAuthentication("client-1");
        OrganisationCertificateDTO cert = certDto(CertificateType.BOOTSTRAP, null);
        when(validationProvider.findByClientId("client-1")).thenReturn(Optional.of(cert));
        when(validationProvider.isActive(cert)).thenReturn(true);
        when(request.getRequestURI()).thenReturn("/api/v1/configuration/consumer");
        setupResponseWriter();

        assertThat(interceptor.preHandle(request, response, handlerMethod)).isFalse();
        verify(response).setStatus(403);
    }

    @Test
    void errorResponse_containsStatusAndMessage() throws Exception {
        setupAuthentication("client-1");
        OrganisationCertificateDTO cert = certDto(CertificateType.AUTOMATED, null);
        when(validationProvider.findByClientId("client-1")).thenReturn(Optional.of(cert));
        when(validationProvider.isActive(cert)).thenReturn(false);
        when(request.getRequestURI()).thenReturn("/api/v1/configuration/consumer");
        StringWriter sw = setupResponseWriter();

        interceptor.preHandle(request, response, handlerMethod);

        verify(response).setStatus(403);
        verify(response).setContentType("application/json");
        String body = sw.toString();
        assertThat(body).contains("403").contains("Organisation certificate is not active");
    }
}
