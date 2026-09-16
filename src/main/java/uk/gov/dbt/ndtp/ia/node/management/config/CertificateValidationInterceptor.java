/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.OrganisationCertificateDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.CertificateType;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.CertificateValidationProvider;

@Component
@Slf4j
public class CertificateValidationInterceptor implements HandlerInterceptor {

    private static final String X509_CERT_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private final CertificateValidationProvider validationProvider;
    private final ObjectMapper objectMapper;

    public CertificateValidationInterceptor(
            CertificateValidationProvider validationProvider, ObjectMapper objectMapper) {
        this.validationProvider = validationProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String clientId = RequestRejectionSupport.extractClientId();
        if (clientId == null) {
            log.warn("No client ID found for request to {}", request.getRequestURI());
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "Client ID required");
            return false;
        }

        OrganisationCertificateDTO cert =
                validationProvider.findByClientId(clientId).orElse(null);
        if (cert == null) {
            log.warn("No certificate record for client {} on {}", clientId, request.getRequestURI());
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "No organisation certificate found");
            return false;
        }

        if (!validationProvider.isActive(cert)) {
            log.warn("Inactive certificate for client {} on {}", clientId, request.getRequestURI());
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "Organisation certificate is not active");
            return false;
        }

        if (cert.getSerialNumber() != null) {
            String rejection = validateSerialNumber(request, cert, clientId);
            if (rejection != null) {
                writeError(response, HttpServletResponse.SC_FORBIDDEN, rejection);
                return false;
            }
        }

        if (cert.getType() == CertificateType.BOOTSTRAP) {
            log.warn("Bootstrap certificate denied access to {}", request.getRequestURI());
            writeError(
                    response, HttpServletResponse.SC_FORBIDDEN, "Bootstrap certificates cannot access this endpoint");
            return false;
        }

        log.debug("Certificate validation successful for client {} on {}", clientId, request.getRequestURI());
        return true;
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

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        RequestRejectionSupport.writeError(
                response, objectMapper, status, message, UUID.randomUUID().toString());
    }
}
