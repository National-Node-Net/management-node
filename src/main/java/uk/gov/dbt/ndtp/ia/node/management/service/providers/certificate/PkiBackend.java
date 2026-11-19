/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate;

import java.math.BigInteger;
import java.time.Instant;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiCertificateExpiredException;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiCertificateNotFoundException;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiPermissionDeniedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiUnavailableException;

/**
 * The certificate authority behind the federation, as the revocation API sees it, so that the API does not depend
 * on Vault directly. {@link VaultPkiService} is the implementation.
 */
public interface PkiBackend {

    /**
     * Revokes a certificate. Revoking a certificate that is already revoked succeeds and returns the original
     * revocation time, so a request can safely be repeated.
     *
     * @param serial the certificate serial number
     * @return the revocation time recorded by the certificate authority
     * @throws PkiCertificateNotFoundException if the certificate authority has no such certificate
     * @throws PkiPermissionDeniedException    if the certificate authority refused the Management Node
     * @throws PkiRejectedException            if the certificate authority refused the request for another reason
     * @throws PkiCertificateExpiredException  if the certificate has expired, so no revocation was recorded
     * @throws PkiUnavailableException         if the certificate authority could not be reached, after retries
     */
    Instant revokeCertificate(BigInteger serial);
}
