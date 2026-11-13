/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status;

import java.security.cert.X509Certificate;
import java.util.Optional;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateStatusUnavailableException;

/** The federation's certificate authority: the only issuer whose certificates the Management Node has a status for. */
public interface FederationCaService {

    /**
     * @return the federation CA
     * @throws CertificateStatusUnavailableException if it has never been loaded and cannot be loaded now
     */
    CertificateIssuer current();

    /**
     * Decides whether the federation CA issued a certificate: the issuer name must be the CA's subject, and the
     * signature must verify with the CA's key.
     *
     * @param certificate a certificate presented by a client
     * @return the federation CA, or empty if another authority issued the certificate
     * @throws CertificateStatusUnavailableException if the federation CA cannot be loaded
     */
    Optional<CertificateIssuer> issuerOf(X509Certificate certificate);
}
