/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.CA;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.CA_KEYS;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.OTHER_CA;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.OTHER_CA_KEYS;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.leafCertificate;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.toX509;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigInteger;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import uk.gov.dbt.ndtp.ia.node.management.config.RevocationProperties;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateStatusUnavailableException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CertificateStatusDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.RevocationTestProperties;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.CertificateIssuer;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.CertificateStatusService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.FederationCaService;

/** Every decision of the revocation check on a request, and what a refusal looks like. */
@ExtendWith(MockitoExtension.class)
class CertificateRevocationFilterTest {

    private static final Instant NOW = Instant.parse("2026-11-12T09:30:00Z");
    private static final BigInteger SERIAL = new BigInteger("39dd2e", 16);
    private static final CertificateIssuer FEDERATION_CA = CertificateIssuer.of(CA);
    private static final X509Certificate FEDERATION_CERT = toX509(leafCertificate(SERIAL, CA, CA_KEYS));
    private static final X509Certificate FOREIGN_CERT = toX509(leafCertificate(SERIAL, OTHER_CA, OTHER_CA_KEYS));
    private static final String CONFIGURATION_PATH = "/api/v1/configuration/producer";

    @Mock
    private CertificateStatusService statusService;

    @Mock
    private FederationCaService federationCa;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SimpleMeterRegistry meterRegistry;
    private ListAppender<ILoggingEvent> logs;
    private MockFilterChain chain;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        chain = new MockFilterChain();
        response = new MockHttpServletResponse();
        logs = new ListAppender<>();
        logs.start();
        logger().addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger().detachAppender(logs);
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(CertificateRevocationFilter.class);
    }

    private CertificateRevocationFilter filter() {
        return filter(RevocationTestProperties.fast());
    }

    private CertificateRevocationFilter filter(RevocationProperties properties) {
        return new CertificateRevocationFilter(
                statusService, federationCa, properties, objectMapper, meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MockHttpServletRequest request(String path, X509Certificate certificate) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("10.0.0.12");
        if (certificate != null) {
            request.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[] {certificate});
        }
        return request;
    }

    private void federationCertificateHasStatus(CertificateStatusDTO status) {
        when(federationCa.issuerOf(FEDERATION_CERT)).thenReturn(Optional.of(FEDERATION_CA));
        when(statusService.getStatus(FEDERATION_CA, SERIAL)).thenReturn(status);
    }

    private void assertPassed() {
        assertNotNull(chain.getRequest(), "the request should reach the rest of the chain");
        assertEquals(200, response.getStatus());
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().startsWith("certificate_revocation_enforcement"));
    }

    private void assertRefused(int httpStatus, String code, String loggedStatus) throws Exception {
        assertNull(chain.getRequest(), "a refused request must not reach authentication or a controller");
        assertEquals(httpStatus, response.getStatus());
        assertThat(response.getContentType()).startsWith("application/json");
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals(httpStatus, body.get("status").asInt());
        assertEquals(code, body.get("code").asText());
        assertThat(body.get("message").asText()).isNotBlank();
        assertThat(body.get("errorId").asText()).isNotBlank();

        assertEquals(
                1,
                meterRegistry
                        .counter("revocation.enforcement.rejections", "status", loggedStatus.toLowerCase())
                        .count());
        List<ILoggingEvent> lines = logs.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("certificate_revocation_enforcement"))
                .toList();
        assertEquals(1, lines.size(), "one security log line per refusal");
        assertEquals(Level.WARN, lines.get(0).getLevel());
        assertThat(lines.get(0).getFormattedMessage())
                .contains("serial=39dd2e")
                .contains("status=" + loggedStatus)
                .contains("path=\"" + CONFIGURATION_PATH + "\"")
                .contains("clientIp=10.0.0.12")
                .contains("timestamp=" + NOW)
                .contains("errorId=" + body.get("errorId").asText());
    }

    @Test
    void goodCertificate_shouldReachTheController() throws Exception {
        federationCertificateHasStatus(CertificateStatusDTO.good());

        filter().doFilter(request(CONFIGURATION_PATH, FEDERATION_CERT), response, chain);

        assertPassed();
    }

    @Test
    void revokedCertificate_shouldBeUnauthorizedWithCertificateRevoked() throws Exception {
        federationCertificateHasStatus(CertificateStatusDTO.revoked(Instant.parse("2026-11-12T09:00:00Z")));

        filter().doFilter(request(CONFIGURATION_PATH, FEDERATION_CERT), response, chain);

        assertRefused(401, "CERTIFICATE_REVOKED", "REVOKED");
        assertThat(logs.list.get(0).getFormattedMessage()).contains("issuer=\"CN=Federation Test CA\"");
    }

    @Test
    void certificateUnknownToTheFederationCa_shouldBeUnauthorizedWithStatusUnknown() throws Exception {
        federationCertificateHasStatus(CertificateStatusDTO.unknown());

        filter().doFilter(request(CONFIGURATION_PATH, FEDERATION_CERT), response, chain);

        assertRefused(401, "CERTIFICATE_STATUS_UNKNOWN", "UNKNOWN");
    }

    @Test
    void certificateOfAnotherAuthority_shouldBeUnauthorizedWithoutAStatusLookup() throws Exception {
        when(federationCa.issuerOf(FOREIGN_CERT)).thenReturn(Optional.empty());

        filter().doFilter(request(CONFIGURATION_PATH, FOREIGN_CERT), response, chain);

        assertRefused(401, "CERTIFICATE_STATUS_UNKNOWN", "UNKNOWN");
        verifyNoInteractions(statusService);
    }

    @Test
    void statusUnavailable_shouldFailClosedWithServiceUnavailable() throws Exception {
        when(federationCa.issuerOf(FEDERATION_CERT)).thenReturn(Optional.of(FEDERATION_CA));
        when(statusService.getStatus(FEDERATION_CA, SERIAL))
                .thenThrow(new CertificateStatusUnavailableException("Vault is down", null));

        filter().doFilter(request(CONFIGURATION_PATH, FEDERATION_CERT), response, chain);

        assertRefused(503, "CERTIFICATE_STATUS_UNAVAILABLE", "UNAVAILABLE");
    }

    @Test
    void federationCaNotLoadable_shouldFailClosedWithServiceUnavailable() throws Exception {
        when(federationCa.issuerOf(FEDERATION_CERT))
                .thenThrow(new CertificateStatusUnavailableException("The federation CA could not be loaded", null));

        filter().doFilter(request(CONFIGURATION_PATH, FEDERATION_CERT), response, chain);

        assertRefused(503, "CERTIFICATE_STATUS_UNAVAILABLE", "UNAVAILABLE");
    }

    @Test
    void unexpectedLookupFailure_shouldNeverBeTreatedAsGood() throws Exception {
        when(federationCa.issuerOf(FEDERATION_CERT)).thenReturn(Optional.of(FEDERATION_CA));
        when(statusService.getStatus(FEDERATION_CA, SERIAL)).thenThrow(new IllegalStateException("boom"));

        filter().doFilter(request(CONFIGURATION_PATH, FEDERATION_CERT), response, chain);

        assertRefused(503, "CERTIFICATE_STATUS_UNAVAILABLE", "UNAVAILABLE");
    }

    @Test
    void statusUnavailable_withFailClosedOff_shouldLetTheRequestThrough() throws Exception {
        when(federationCa.issuerOf(FEDERATION_CERT)).thenReturn(Optional.of(FEDERATION_CA));
        when(statusService.getStatus(FEDERATION_CA, SERIAL))
                .thenThrow(new CertificateStatusUnavailableException("Vault is down", null));

        filter(RevocationTestProperties.withEnforcement(false, List.of()))
                .doFilter(request(CONFIGURATION_PATH, FEDERATION_CERT), response, chain);

        assertPassed();
    }

    @Test
    void revokedCertificate_withFailClosedOff_shouldStillBeRefused() throws Exception {
        federationCertificateHasStatus(CertificateStatusDTO.revoked(NOW));

        filter(RevocationTestProperties.withEnforcement(false, List.of()))
                .doFilter(request(CONFIGURATION_PATH, FEDERATION_CERT), response, chain);

        assertRefused(401, "CERTIFICATE_REVOKED", "REVOKED");
    }

    @Test
    void requestWithoutClientCertificate_shouldNotBeChecked() throws Exception {
        filter().doFilter(request(CONFIGURATION_PATH, null), response, chain);

        assertPassed();
        verifyNoInteractions(statusService, federationCa);
    }

    /** The check itself, reached without shouldNotFilter, must not fail on a request without a certificate. */
    @Test
    void filterBodyWithoutClientCertificate_shouldLetTheRequestThrough() throws Exception {
        filter().doFilterInternal(request(CONFIGURATION_PATH, null), response, chain);

        assertPassed();
        verifyNoInteractions(statusService, federationCa);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/certificate/ocsp", "/actuator/health", "/actuator/health/liveness"})
    void excludedPath_shouldNotBeCheckedEvenWithARevokedCertificate(String path) throws Exception {
        filter().doFilter(request(path, FEDERATION_CERT), response, chain);

        assertPassed();
        verifyNoInteractions(statusService, federationCa);
    }

    @Test
    void pathThatOnlyStartsLikeAnExcludedOne_shouldBeChecked() throws Exception {
        when(federationCa.issuerOf(any())).thenReturn(Optional.empty());

        filter().doFilter(request("/api/v1/certificate/ocsp-other", FEDERATION_CERT), response, chain);

        assertEquals(401, response.getStatus());
    }

    @Test
    void certificateOfAnExemptIssuer_shouldPassWithoutACheck() throws Exception {
        filter(RevocationTestProperties.withEnforcement(true, List.of("CN=Other Test CA")))
                .doFilter(request(CONFIGURATION_PATH, FOREIGN_CERT), response, chain);

        assertPassed();
        verifyNoInteractions(statusService, federationCa);
    }

    @Test
    void exemptIssuers_shouldNotExemptTheFederationCa() throws Exception {
        federationCertificateHasStatus(CertificateStatusDTO.revoked(NOW));

        filter(RevocationTestProperties.withEnforcement(true, List.of("CN=Other Test CA")))
                .doFilter(request(CONFIGURATION_PATH, FEDERATION_CERT), response, chain);

        assertRefused(401, "CERTIFICATE_REVOKED", "REVOKED");
    }

    @Test
    void pathInTheLogLine_shouldBeEscaped() throws Exception {
        when(federationCa.issuerOf(FEDERATION_CERT)).thenReturn(Optional.empty());

        filter().doFilter(request("/api/v1/x\"\nforged=1", FEDERATION_CERT), response, chain);

        assertThat(logs.list.get(0).getFormattedMessage())
                .contains("path=\"/api/v1/x\\\"\\u000aforged=1\"")
                .doesNotContain("\n");
    }
}
