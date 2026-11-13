/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status;

import java.math.BigInteger;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateStatusUnavailableException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CertificateStatusDTO;

/**
 * The single source of certificate revocation status in the Management Node, used by the revocation check on
 * requests and by the OCSP responder. Statuses are cached by issuer and serial for
 * {@code application.revocation.cache.ttl}; a failed lookup is never cached.
 */
public interface CertificateStatusService {

    /**
     * @param issuer the certificate authority that issued the certificate
     * @param serial the certificate serial number
     * @return the status, with the time it was looked up
     * @throws CertificateStatusUnavailableException if the status is not cached and the backend cannot be reached
     */
    CertificateStatusDTO getStatus(CertificateIssuer issuer, BigInteger serial);

    /**
     * Forgets the cached status of a certificate, so the next request looks it up again. Called when the
     * certificate is revoked through the Management Node.
     *
     * @param issuer the certificate authority that issued the certificate
     * @param serial the certificate serial number
     */
    void evict(CertificateIssuer issuer, BigInteger serial);
}
