/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.stream.Stream;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import uk.gov.dbt.ndtp.ia.node.management.exception.AccessRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OrganisationCertificateDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateType;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.CertificateValidationProvider;

/**
 * Covers the organisation certificate check as method advice: which calls it judges, each reason it
 * refuses, and that a refusal stops the call before the handler runs.
 */
@ExtendWith(MockitoExtension.class)
class CertificateValidationInterceptorTest {

    private static final String PROTECTED_PATH = "/api/v1/configuration/consumer";
    private static final String HANDLED = "handled";

    @Mock
    private CertificateValidationProvider validationProvider;

    @Mock
    private MethodInvocation invocation;

    private MockHttpServletRequest request;
    private CertificateValidationInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new CertificateValidationInterceptor(validationProvider);
        request = new MockHttpServletRequest("GET", PROTECTED_PATH);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String clientId) {
        EnhancedPrincipal principal = new EnhancedPrincipal("subject", clientId, "test-organisation");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal, null));
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

    private void activeCertificate(CertificateType type, String serial) {
        OrganisationCertificateDTO cert = certDto(type, serial);
        when(validationProvider.findByClientId("client-1")).thenReturn(Optional.of(cert));
        when(validationProvider.isActive(cert)).thenReturn(true);
    }

    private void presentCertificateWithSerial(String hexSerial) {
        X509Certificate presented = mock(X509Certificate.class);
        when(presented.getSerialNumber()).thenReturn(new BigInteger(hexSerial, 16));
        request.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[] {presented});
    }

    private void assertPassesThrough() throws Throwable {
        when(invocation.proceed()).thenReturn(HANDLED);
        assertThat(interceptor.invoke(invocation)).isEqualTo(HANDLED);
    }

    private void assertRejectedWith(String message) throws Throwable {
        assertThatThrownBy(() -> interceptor.invoke(invocation))
                .isInstanceOf(AccessRejectedException.class)
                .hasMessage(message)
                .satisfies(rejection -> assertThat(((AccessRejectedException) rejection).getErrorId())
                        .isNotBlank());
        verify(invocation, never()).proceed();
    }

    // ---------------------------------------------------------------------------------------
    // Which calls are judged
    // ---------------------------------------------------------------------------------------

    @Test
    void pathOutsideProtectedPaths_proceedsWithoutCheckingCertificates() throws Throwable {
        request.setRequestURI("/api/v1/product/discover");

        assertPassesThrough();
        verifyNoInteractions(validationProvider);
    }

    @Test
    void callOutsideARequest_proceedsWithoutCheckingCertificates() throws Throwable {
        RequestContextHolder.resetRequestAttributes();

        assertPassesThrough();
        verifyNoInteractions(validationProvider);
    }

    // ---------------------------------------------------------------------------------------
    // Refusals
    // ---------------------------------------------------------------------------------------

    @Test
    void noAuthentication_isRejected() throws Throwable {
        assertRejectedWith("Client ID required");
    }

    @Test
    void nonEnhancedPrincipal_isRejected() throws Throwable {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("plain-string-principal", null));

        assertRejectedWith("Client ID required");
    }

    @Test
    void emptyClientId_isRejected() throws Throwable {
        authenticateAs("");

        assertRejectedWith("Client ID required");
    }

    @Test
    void noCertRecord_isRejected() throws Throwable {
        authenticateAs("client-1");
        when(validationProvider.findByClientId("client-1")).thenReturn(Optional.empty());

        assertRejectedWith("No organisation certificate found");
    }

    @Test
    void inactiveCert_isRejected() throws Throwable {
        authenticateAs("client-1");
        OrganisationCertificateDTO cert = certDto(CertificateType.AUTOMATED, null);
        when(validationProvider.findByClientId("client-1")).thenReturn(Optional.of(cert));
        when(validationProvider.isActive(cert)).thenReturn(false);

        assertRejectedWith("Organisation certificate is not active");
    }

    @Test
    void mismatchedSerialNumber_isRejected() throws Throwable {
        authenticateAs("client-1");
        activeCertificate(CertificateType.AUTOMATED, "abc123");
        presentCertificateWithSerial("def456");

        assertRejectedWith("Certificate serial number mismatch");
    }

    @Test
    void noCertificateWhenSerialExpected_isRejected() throws Throwable {
        authenticateAs("client-1");
        activeCertificate(CertificateType.AUTOMATED, "abc123");

        assertRejectedWith("Client certificate required");
    }

    @Test
    void bootstrapCert_isAlwaysRejected() throws Throwable {
        authenticateAs("client-1");
        activeCertificate(CertificateType.BOOTSTRAP, null);

        assertRejectedWith("Bootstrap certificates cannot access this endpoint");
    }

    // ---------------------------------------------------------------------------------------
    // Pass-through
    // ---------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(
            value = CertificateType.class,
            names = {"AUTOMATED", "MANUAL"})
    void activeCert_passesThrough(CertificateType type) throws Throwable {
        authenticateAs("client-1");
        activeCertificate(type, null);

        assertPassesThrough();
    }

    static Stream<Arguments> serialNumberFormats() {
        return Stream.of(
                Arguments.of("abc123", "plain hex match"),
                Arguments.of("ABC123", "case-insensitive match"),
                Arguments.of("ab:c1:23", "colon-separated match"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("serialNumberFormats")
    void matchingSerialNumber_passesThrough(String storedSerial, String description) throws Throwable {
        authenticateAs("client-1");
        activeCertificate(CertificateType.AUTOMATED, storedSerial);
        presentCertificateWithSerial("abc123");

        assertPassesThrough();
    }

    /** No stored serial means nothing to compare, so a missing client certificate is not a refusal. */
    @Test
    void nullSerialNumber_skipsTheSerialCheck() throws Throwable {
        authenticateAs("client-1");
        activeCertificate(CertificateType.AUTOMATED, null);

        assertPassesThrough();
    }
}
