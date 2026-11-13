/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp;

import java.math.BigInteger;
import org.bouncycastle.cert.X509CertificateHolder;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateStatusUnavailableException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CertificateStatusDTO;

/**
 * Source of certificate revocation status, behind {@code CertificateStatusService}, which caches its answers.
 * Implementations are selected with {@code application.ocsp.status-backend}.
 */
public interface CertificateStatusProvider {

    /**
     * Looks up the current status of a certificate. Implementations must not cache results themselves: the
     * caching, and its eviction when a certificate is revoked, belong to {@code CertificateStatusService}.
     *
     * @param serial the certificate serial number
     * @param issuer the CA certificate the request was matched against
     * @return the certificate status
     * @throws CertificateStatusUnavailableException if the backend cannot be reached
     */
    CertificateStatusDTO getStatus(BigInteger serial, X509CertificateHolder issuer);
}
