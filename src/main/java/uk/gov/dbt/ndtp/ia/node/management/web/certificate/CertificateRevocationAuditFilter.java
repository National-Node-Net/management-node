/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.certificate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.RevokeCertificateRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.CachedBodyHttpServletRequest;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.PolicyBodyCachingFilter;

/**
 * Writes one audit line for every authenticated revocation attempt, whatever its outcome:
 *
 * <pre>
 * certificate_revocation_audit caller="..." clientId="..." organisation="..." serial="..." reason="..."
 *     comment="..." outcome=REVOKED httpStatus=200 timestamp=2026-10-12T13:15:02Z
 * </pre>
 *
 * <p>A servlet filter rather than code in the revocation service, because two refusals never reach the service:
 * a caller without the role is refused by method security, and an invalid body by validation before the
 * controller runs. The filter sits behind Spring Security's filter chain, so the caller is known; a request
 * without a valid token is answered {@code 401} before it gets here, and Spring Security logs it.
 *
 * <p>The body is buffered so the audit line can show what was asked for, even when the request was invalid. Its
 * values come from the caller, so they are quoted, escaped and cut to length: a comment cannot forge an audit line
 * of its own. A body above {@link PolicyBodyCachingFilter#MAX_BODY_BYTES}, or without a declared length, is not
 * buffered, and its fields are logged as {@code -}.
 */
@Component
@Slf4j
public class CertificateRevocationAuditFilter extends OncePerRequestFilter {

    /** The path audited, within the application. */
    public static final String REVOKE_PATH = "/api/v1/certificate/revoke";

    private static final String MISSING = "-";
    private static final int MAX_SERIAL_LENGTH = 100;
    private static final int MAX_REASON_LENGTH = 50;
    private static final String TRUNCATED = "...[truncated]";

    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public CertificateRevocationAuditFilter(ObjectMapper objectMapper) {
        this(objectMapper, Clock.systemUTC());
    }

    CertificateRevocationAuditFilter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
                || !REVOKE_PATH.equals(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        byte[] body = null;
        HttpServletRequest forwarded = request;
        long length = request.getContentLengthLong();
        if (length > 0 && length <= PolicyBodyCachingFilter.MAX_BODY_BYTES) {
            body = StreamUtils.copyToByteArray(request.getInputStream());
            forwarded = new CachedBodyHttpServletRequest(request, body);
        }

        boolean failed = true;
        try {
            filterChain.doFilter(forwarded, response);
            failed = false;
        } finally {
            // An exception escaping the chain becomes a 500 once it leaves this filter.
            audit(body, failed ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus());
        }
    }

    private void audit(byte[] body, int httpStatus) {
        JsonNode request = parse(body);
        EnhancedPrincipal principal = principal();
        log.info(
                "certificate_revocation_audit caller={} clientId={} organisation={} serial={} reason={} comment={}"
                        + " outcome={} httpStatus={} timestamp={}",
                quote(principal == null ? null : principal.subject(), MAX_SERIAL_LENGTH),
                quote(principal == null ? null : principal.clientId(), MAX_SERIAL_LENGTH),
                quote(principal == null ? null : principal.organisation(), MAX_SERIAL_LENGTH),
                quote(field(request, "serialNumber"), MAX_SERIAL_LENGTH),
                quote(field(request, "reason"), MAX_REASON_LENGTH),
                quote(field(request, "comment"), RevokeCertificateRequestDTO.MAX_COMMENT_LENGTH),
                outcome(httpStatus),
                httpStatus,
                clock.instant());
    }

    /** What the status says happened. A 409 is either an already revoked or an expired certificate. */
    static String outcome(int httpStatus) {
        return switch (httpStatus) {
            case 200 -> "REVOKED";
            case 400 -> "INVALID_REQUEST";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 429 -> "RATE_LIMITED";
            case 502 -> "VAULT_REJECTED";
            case 503 -> "VAULT_UNAVAILABLE";
            default -> "FAILED";
        };
    }

    private JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String field(JsonNode request, String name) {
        if (request == null) {
            return null;
        }
        JsonNode value = request.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isValueNode() ? value.asText() : value.toString();
    }

    private static EnhancedPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof EnhancedPrincipal principal
                ? principal
                : null;
    }

    /**
     * A value as it appears in the audit line: {@code -} when absent, otherwise cut to length, in double quotes,
     * with quotes, backslashes, control characters and line separators escaped, so it stays on one line and
     * inside its quotes.
     */
    static String quote(String value, int maxLength) {
        if (value == null) {
            return MISSING;
        }
        boolean truncated = value.length() > maxLength;
        String kept = truncated ? value.substring(0, maxLength) : value;
        StringBuilder quoted = new StringBuilder(kept.length() + 2).append('"');
        kept.codePoints().forEach(c -> {
            if (c == '"' || c == '\\') {
                quoted.append('\\').append((char) c);
            } else if (Character.isISOControl(c)
                    || Character.getType(c) == Character.LINE_SEPARATOR
                    || Character.getType(c) == Character.PARAGRAPH_SEPARATOR) {
                quoted.append(String.format("\\u%04x", c));
            } else {
                quoted.appendCodePoint(c);
            }
        });
        if (truncated) {
            quoted.append(TRUNCATED);
        }
        return quoted.append('"').toString();
    }
}
