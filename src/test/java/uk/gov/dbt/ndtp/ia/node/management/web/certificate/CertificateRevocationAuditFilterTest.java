/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StreamUtils;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;

class CertificateRevocationAuditFilterTest {

    private static final Instant NOW = Instant.parse("2026-10-12T13:15:02Z");
    private static final String BODY = "{\"serialNumber\":\"39:dd:2e\",\"reason\":\"KEY_COMPROMISE\","
            + "\"comment\":\"Private key exposed on build server\"}";

    private CertificateRevocationAuditFilter filter;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        filter = new CertificateRevocationAuditFilter(new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        logs = new ListAppender<>();
        logs.start();
        logger().addAppender(logs);
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken(
                        new EnhancedPrincipal("admin-a", "FEDERATOR_ENV", "ENV"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_management-node:revoke_certificate"))));
    }

    @AfterEach
    void tearDown() {
        logger().detachAppender(logs);
        SecurityContextHolder.clearContext();
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(CertificateRevocationAuditFilter.class);
    }

    private static MockHttpServletRequest revokeRequest(String body) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", CertificateRevocationAuditFilter.REVOKE_PATH);
        request.setContentType("application/json");
        if (body != null) {
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
        }
        return request;
    }

    private static FilterChain respondingWith(int status) {
        return (request, response) -> ((MockHttpServletResponse) response).setStatus(status);
    }

    private List<String> auditLines() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("certificate_revocation_audit "))
                .toList();
    }

    private String onlyAuditLine() {
        List<String> lines = auditLines();
        assertThat(lines).hasSize(1);
        return lines.get(0);
    }

    @Test
    void successfulRevocation_shouldAuditCallerOrganisationRequestOutcomeAndTime() throws Exception {
        filter.doFilter(revokeRequest(BODY), new MockHttpServletResponse(), respondingWith(200));

        assertEquals(
                "certificate_revocation_audit caller=\"admin-a\" clientId=\"FEDERATOR_ENV\" organisation=\"ENV\""
                        + " serial=\"39:dd:2e\" reason=\"KEY_COMPROMISE\""
                        + " comment=\"Private key exposed on build server\""
                        + " outcome=REVOKED httpStatus=200 timestamp=2026-10-12T13:15:02Z",
                onlyAuditLine());
    }

    @ParameterizedTest
    @CsvSource({
        "400, INVALID_REQUEST",
        "403, FORBIDDEN",
        "404, NOT_FOUND",
        "409, CONFLICT",
        "429, RATE_LIMITED",
        "500, FAILED",
        "502, VAULT_REJECTED",
        "503, VAULT_UNAVAILABLE"
    })
    void everyOutcome_shouldBeAudited(int status, String outcome) throws Exception {
        filter.doFilter(revokeRequest(BODY), new MockHttpServletResponse(), respondingWith(status));

        assertThat(onlyAuditLine()).contains(" outcome=" + outcome + " httpStatus=" + status + " ");
    }

    @Test
    void handlerStillReadsTheBufferedBody() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(
                revokeRequest(BODY),
                new MockHttpServletResponse(),
                (request, response) ->
                        seen.set(StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8)));

        assertEquals(BODY, seen.get());
    }

    @Test
    void invalidRequest_shouldAuditTheValuesAsSent() throws Exception {
        filter.doFilter(
                revokeRequest("{\"serialNumber\":\"not-a-serial\",\"reason\":\"LOST\"}"),
                new MockHttpServletResponse(),
                respondingWith(400));

        assertThat(onlyAuditLine())
                .contains("serial=\"not-a-serial\" reason=\"LOST\" comment=- outcome=INVALID_REQUEST");
    }

    @Test
    void bodyThatIsNotJson_shouldAuditTheFieldsAsMissing() throws Exception {
        filter.doFilter(revokeRequest("{\"serialNumber\": "), new MockHttpServletResponse(), respondingWith(400));

        assertThat(onlyAuditLine()).contains("serial=- reason=- comment=- outcome=INVALID_REQUEST");
    }

    @Test
    void commentWithLineBreaksAndQuotes_cannotForgeAnotherAuditLine() throws Exception {
        String forged = "ok\" outcome=REVOKED httpStatus=200\ncertificate_revocation_audit caller=\"someone-else\"";
        String body = new ObjectMapper()
                .writeValueAsString(
                        java.util.Map.of("serialNumber", "39:dd", "reason", "UNSPECIFIED", "comment", forged));

        filter.doFilter(revokeRequest(body), new MockHttpServletResponse(), respondingWith(403));

        String line = onlyAuditLine();
        assertThat(line)
                .doesNotContain("\n")
                .endsWith("outcome=FORBIDDEN httpStatus=403 timestamp=2026-10-12T13:15:02Z");
        assertThat(line)
                .contains("comment=\"ok\\\" outcome=REVOKED httpStatus=200\\u000acertificate_revocation_audit"
                        + " caller=\\\"someone-else\\\"\"");
    }

    @Test
    void overlongComment_shouldBeCutAndMarked() throws Exception {
        String body = "{\"serialNumber\":\"39:dd\",\"reason\":\"UNSPECIFIED\",\"comment\":\"" + "x".repeat(600) + "\"}";

        filter.doFilter(revokeRequest(body), new MockHttpServletResponse(), respondingWith(400));

        assertThat(onlyAuditLine()).contains("comment=\"" + "x".repeat(500) + "...[truncated]\"");
    }

    @Test
    void exceptionEscapingTheChain_shouldBeAuditedAsFailedAndRethrown() {
        FilterChain failing = (request, response) -> {
            throw new ServletException("boom");
        };

        assertThrows(
                ServletException.class,
                () -> filter.doFilter(revokeRequest(BODY), new MockHttpServletResponse(), failing));
        assertThat(onlyAuditLine()).contains("outcome=FAILED httpStatus=500");
    }

    @Test
    void withoutAnAuthenticatedPrincipal_shouldAuditTheCallerAsMissing() throws Exception {
        SecurityContextHolder.clearContext();

        filter.doFilter(revokeRequest(BODY), new MockHttpServletResponse(), respondingWith(403));

        assertThat(onlyAuditLine()).startsWith("certificate_revocation_audit caller=- clientId=- organisation=- ");
    }

    @Test
    void otherRequests_shouldNotBeAudited() throws Exception {
        filter.doFilter(
                new MockHttpServletRequest("POST", "/api/v1/certificate/csr/sign"),
                new MockHttpServletResponse(),
                respondingWith(200));
        filter.doFilter(
                new MockHttpServletRequest("GET", CertificateRevocationAuditFilter.REVOKE_PATH),
                new MockHttpServletResponse(),
                respondingWith(405));

        assertThat(auditLines()).isEmpty();
    }
}
