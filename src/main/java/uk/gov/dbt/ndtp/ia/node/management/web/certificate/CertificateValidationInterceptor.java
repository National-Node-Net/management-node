/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.certificate;

import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import uk.gov.dbt.ndtp.ia.node.management.exception.AccessRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OrganisationCertificateDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateType;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.CertificateValidationProvider;
import uk.gov.dbt.ndtp.ia.node.management.web.RequestContextSupport;
import uk.gov.dbt.ndtp.ia.node.management.web.RequestRejectionSupport;

/**
 * Requires the calling client's organisation to hold an active, matching, non-bootstrap
 * certificate before a protected endpoint runs.
 *
 * <p>A method interceptor rather than a {@code HandlerInterceptor}: handler interceptors run
 * before the handler method is invoked, and so before {@code @PreAuthorize}. Advising the method,
 * ordered after method security (see {@code RequestEnforcementConfig}), means a caller without the
 * required role is refused by authorization and never reaches this check.
 *
 * <p>Applies to requests under {@link #PROTECTED_PATHS}, matched against the path within the
 * application; every other advised call proceeds untouched.
 */
@Component
@Slf4j
public class CertificateValidationInterceptor implements MethodInterceptor {

    /** Endpoints that require a valid organisation certificate. */
    public static final List<String> PROTECTED_PATHS = List.of("/api/v1/configuration/**");

    private static final String X509_CERT_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private final CertificateValidationProvider validationProvider;
    private final List<PathPattern> protectedPatterns;

    public CertificateValidationInterceptor(CertificateValidationProvider validationProvider) {
        this.validationProvider = validationProvider;
        this.protectedPatterns = PROTECTED_PATHS.stream()
                .map(PathPatternParser.defaultInstance::parse)
                .toList();
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        HttpServletRequest request = RequestContextSupport.currentRequest().orElse(null);
        if (request == null || !isProtected(request)) {
            return invocation.proceed();
        }
        validate(request);
        return invocation.proceed();
    }

    private boolean isProtected(HttpServletRequest request) {
        PathContainer path = PathContainer.parsePath(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
        return protectedPatterns.stream().anyMatch(pattern -> pattern.matches(path));
    }

    private void validate(HttpServletRequest request) {
        String clientId = RequestRejectionSupport.extractClientId();
        if (clientId == null) {
            log.warn("No client ID found for request to {}", request.getRequestURI());
            throw reject("Client ID required");
        }

        OrganisationCertificateDTO cert =
                validationProvider.findByClientId(clientId).orElse(null);
        if (cert == null) {
            log.warn("No certificate record for client {} on {}", clientId, request.getRequestURI());
            throw reject("No organisation certificate found");
        }

        if (!validationProvider.isActive(cert)) {
            log.warn("Inactive certificate for client {} on {}", clientId, request.getRequestURI());
            throw reject("Organisation certificate is not active");
        }

        if (cert.getSerialNumber() != null) {
            String rejection = validateSerialNumber(request, cert, clientId);
            if (rejection != null) {
                throw reject(rejection);
            }
        }

        if (cert.getType() == CertificateType.BOOTSTRAP) {
            log.warn("Bootstrap certificate denied access to {}", request.getRequestURI());
            throw reject("Bootstrap certificates cannot access this endpoint");
        }

        log.debug("Certificate validation successful for client {} on {}", clientId, request.getRequestURI());
    }

    private String validateSerialNumber(HttpServletRequest request, OrganisationCertificateDTO cert, String clientId) {
        X509Certificate[] certs = (X509Certificate[]) request.getAttribute(X509_CERT_ATTRIBUTE);
        if (certs == null || certs.length == 0) {
            return "Client certificate required";
        }
        String presentedSerial = certs[0].getSerialNumber().toString(16);
        String storedSerial = cert.getSerialNumber().replace(":", "");
        if (!presentedSerial.equalsIgnoreCase(storedSerial)) {
            log.warn(
                    "Serial mismatch for client {}: expected={}, presented={}",
                    clientId,
                    cert.getSerialNumber(),
                    presentedSerial);
            return "Certificate serial number mismatch";
        }
        return null;
    }

    private static AccessRejectedException reject(String message) {
        return new AccessRejectedException(message, UUID.randomUUID().toString());
    }
}
