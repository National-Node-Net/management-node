/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.certificate;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.security.auth.x500.X500Principal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import uk.gov.dbt.ndtp.ia.node.management.config.RevocationProperties;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CertificateStatusDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.CertificateIssuer;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.CertificateStatusService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.FederationCaService;
import uk.gov.dbt.ndtp.ia.node.management.utils.cryptography.CertificateSerials;
import uk.gov.dbt.ndtp.ia.node.management.web.RequestRejectionSupport;

/**
 * Refuses every request whose client certificate is revoked, before it reaches authentication or a controller.
 *
 * <p>A request is let through without a check when it presents no client certificate (an operator with only a
 * token, or mTLS switched off), when its path is excluded, or when its certificate was issued by an exempt
 * authority. Otherwise the certificate's status decides:
 *
 * <ul>
 *   <li>{@code GOOD} - the request continues;
 *   <li>{@code REVOKED} - {@code 401}, code {@code CERTIFICATE_REVOKED};
 *   <li>{@code UNKNOWN}, which includes a certificate the federation CA did not issue - {@code 401}, code
 *       {@code CERTIFICATE_STATUS_UNKNOWN};
 *   <li>no status, because the backend is unreachable and nothing is cached - {@code 503}, code
 *       {@code CERTIFICATE_STATUS_UNAVAILABLE}, unless {@code fail-closed} is off.
 * </ul>
 *
 * <p>Every refusal writes one security log line and counts in {@code revocation.enforcement.rejections}.
 *
 * <p>Not a {@code @Component}: it exists only while {@code application.revocation.enforcement.enabled} is true,
 * and is placed in Spring Security's chain rather than registered as a servlet filter of its own. See
 * {@code RevocationEnforcementConfig}.
 */
@Slf4j
public class CertificateRevocationFilter extends OncePerRequestFilter {

    public static final String CODE_REVOKED = "CERTIFICATE_REVOKED";
    public static final String CODE_UNKNOWN = "CERTIFICATE_STATUS_UNKNOWN";
    public static final String CODE_UNAVAILABLE = "CERTIFICATE_STATUS_UNAVAILABLE";

    private static final String X509_CERT_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";
    private static final String REJECTIONS = "revocation.enforcement.rejections";
    private static final int MAX_LOGGED_LENGTH = 300;

    private final CertificateStatusService statusService;
    private final FederationCaService federationCa;
    private final boolean failClosed;
    private final List<PathPattern> excludedPatterns;
    private final Set<X500Principal> exemptIssuers;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public CertificateRevocationFilter(
            CertificateStatusService statusService,
            FederationCaService federationCa,
            RevocationProperties properties,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            Clock clock) {
        RevocationProperties.Enforcement enforcement = properties.enforcement();
        this.statusService = statusService;
        this.federationCa = federationCa;
        this.failClosed = enforcement.failClosed();
        // An excluded path covers what lies beneath it too, e.g. /actuator/health/liveness.
        this.excludedPatterns = enforcement.excludedPaths().stream()
                .flatMap(path -> path.endsWith("/**") ? Stream.of(path) : Stream.of(path, path + "/**"))
                .map(PathPatternParser.defaultInstance::parse)
                .toList();
        this.exemptIssuers =
                enforcement.exemptIssuers().stream().map(X500Principal::new).collect(Collectors.toUnmodifiableSet());
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (clientCertificate(request) == null) {
            return true;
        }
        PathContainer path = PathContainer.parsePath(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
        return excludedPatterns.stream().anyMatch(pattern -> pattern.matches(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        X509Certificate certificate = clientCertificate(request);
        if (exemptIssuers.contains(certificate.getIssuerX500Principal())) {
            log.debug("Client certificate issued by exempt authority {}; not checked", issuerName(certificate));
            filterChain.doFilter(request, response);
            return;
        }

        Decision decision = decide(certificate);
        if (decision == Decision.UNAVAILABLE && !failClosed) {
            log.warn(
                    "Certificate status not available for serial {} on {}; fail-closed is off, letting the request"
                            + " through",
                    serialOf(certificate),
                    CertificateRevocationAuditFilter.quote(request.getRequestURI(), MAX_LOGGED_LENGTH));
            decision = Decision.GOOD;
        }
        if (decision == Decision.GOOD) {
            filterChain.doFilter(request, response);
            return;
        }
        reject(request, response, certificate, decision);
    }

    private Decision decide(X509Certificate certificate) {
        try {
            Optional<CertificateIssuer> issuer = federationCa.issuerOf(certificate);
            if (issuer.isEmpty()) {
                return Decision.UNKNOWN;
            }
            CertificateStatusDTO status = statusService.getStatus(issuer.get(), certificate.getSerialNumber());
            return switch (status.getStatus()) {
                case GOOD -> Decision.GOOD;
                case REVOKED -> Decision.REVOKED;
                case UNKNOWN -> Decision.UNKNOWN;
            };
        } catch (RuntimeException e) {
            // Whatever went wrong, the status is not known: never treat it as good.
            log.error("Certificate status lookup failed for serial {}", serialOf(certificate), e);
            return Decision.UNAVAILABLE;
        }
    }

    private void reject(
            HttpServletRequest request, HttpServletResponse response, X509Certificate certificate, Decision decision)
            throws IOException {
        String errorId = UUID.randomUUID().toString();
        meterRegistry
                .counter(REJECTIONS, "status", decision.name().toLowerCase(Locale.ROOT))
                .increment();
        log.warn(
                "certificate_revocation_enforcement serial={} issuer={} status={} path={} clientIp={} timestamp={}"
                        + " errorId={}",
                serialOf(certificate),
                CertificateRevocationAuditFilter.quote(issuerName(certificate), MAX_LOGGED_LENGTH),
                decision.name(),
                CertificateRevocationAuditFilter.quote(request.getRequestURI(), MAX_LOGGED_LENGTH),
                request.getRemoteAddr(),
                clock.instant(),
                errorId);
        RequestRejectionSupport.writeError(
                response, objectMapper, decision.httpStatus, decision.code, decision.message, errorId);
    }

    private static X509Certificate clientCertificate(HttpServletRequest request) {
        return request.getAttribute(X509_CERT_ATTRIBUTE) instanceof X509Certificate[] chain && chain.length > 0
                ? chain[0]
                : null;
    }

    private static String serialOf(X509Certificate certificate) {
        return CertificateSerials.toStored(certificate.getSerialNumber());
    }

    private static String issuerName(X509Certificate certificate) {
        return certificate.getIssuerX500Principal().getName();
    }

    private enum Decision {
        GOOD(HttpServletResponse.SC_OK, null, null),
        REVOKED(HttpServletResponse.SC_UNAUTHORIZED, CODE_REVOKED, "Client certificate has been revoked"),
        UNKNOWN(
                HttpServletResponse.SC_UNAUTHORIZED,
                CODE_UNKNOWN,
                "Client certificate was not issued by the federation certificate authority, or is not known to it"),
        UNAVAILABLE(
                HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                CODE_UNAVAILABLE,
                "Client certificate status could not be determined");

        private final int httpStatus;
        private final String code;
        private final String message;

        Decision(int httpStatus, String code, String message) {
            this.httpStatus = httpStatus;
            this.code = code;
            this.message = message;
        }
    }
}
