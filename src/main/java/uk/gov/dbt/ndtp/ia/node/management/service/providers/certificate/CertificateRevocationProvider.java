/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate;

import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.RevokeCertificateRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.RevokeCertificateResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;

/** Revokes organisation certificates on behalf of an organisation administrator. */
public interface CertificateRevocationProvider {

    /**
     * Revokes a certificate of the caller's organisation: in the PKI backend first, which is the source of truth,
     * then in {@code organisation_certificate}. A request that failed after the PKI backend revoked the
     * certificate can be repeated, and records the revocation then.
     *
     * @param request the serial, reason and optional comment
     * @param caller  the authenticated administrator; their organisation comes from the token
     * @return the revocation recorded
     */
    RevokeCertificateResponseDTO revoke(RevokeCertificateRequestDTO request, EnhancedPrincipal caller);
}
