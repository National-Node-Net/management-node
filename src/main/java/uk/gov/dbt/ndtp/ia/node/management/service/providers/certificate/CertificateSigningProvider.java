/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate;

import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.SignCertResponseDTO;

/**
 * Provides certificate signing orchestration, including record updates and audit events.
 */
public interface CertificateSigningProvider {

    /**
     * Sign a CSR and record the result against the caller's organisation certificate.
     *
     * @param csrPem the CSR in PEM format
     * @param clientId the IDP client ID of the calling organisation
     * @return a DTO containing the signed certificate and its chain
     */
    SignCertResponseDTO signAndRecord(String csrPem, String clientId);

    /**
     * Issue a short-lived bootstrap certificate package for the given organisation.
     * Signs the caller-provided CSR with a short TTL and bootstrap OID marker,
     * sets the organisation's certificate record to BOOTSTRAP type with renewal enabled,
     * and packages the signed certificate and CA chain as PEM files in a ZIP archive.
     *
     * @param organisationId the ID of the target organisation
     * @param csrPem the Certificate Signing Request in PEM format
     * @param performedBy the client ID of the caller requesting the bootstrap
     * @return a ZIP archive as a byte array containing certificate.pem and ca-chain.pem
     */
    byte[] issueBootstrapPackage(Long organisationId, String csrPem, String performedBy);
}
