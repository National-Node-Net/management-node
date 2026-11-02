/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp;

import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OcspResponseDTO;

/**
 * OCSP responder (RFC 6960). Answers DER-encoded OCSP requests with DER-encoded responses signed by the
 * delegated OCSP signer.
 */
public interface OcspResponderService {

    /**
     * Answers a DER-encoded OCSP request. Never throws: invalid requests and backend failures are answered with
     * the unsigned OCSP statuses {@code malformedRequest}, {@code tryLater} or {@code internalError}.
     *
     * @param request     the DER-encoded OCSP request body (may be {@code null})
     * @param requesterIp the requester's IP address, for logging
     * @return the DER-encoded OCSP response
     */
    OcspResponseDTO respond(byte[] request, String requesterIp);
}
