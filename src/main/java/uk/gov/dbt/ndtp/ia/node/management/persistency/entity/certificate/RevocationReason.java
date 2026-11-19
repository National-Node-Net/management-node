/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate;

/**
 * Why a certificate was revoked: the subset of the RFC 5280 {@code CRLReason} values an organisation
 * administrator may give. Vault does not record a reason, so it is kept on the organisation certificate
 * and returned by the OCSP responder.
 */
public enum RevocationReason {
    UNSPECIFIED(0),
    KEY_COMPROMISE(1),
    AFFILIATION_CHANGED(3),
    SUPERSEDED(4),
    CESSATION_OF_OPERATION(5);

    private final int crlReasonCode;

    RevocationReason(int crlReasonCode) {
        this.crlReasonCode = crlReasonCode;
    }

    /**
     * @return the RFC 5280 {@code CRLReason} code, e.g. {@code 1} for {@link #KEY_COMPROMISE}
     */
    public int crlReasonCode() {
        return crlReasonCode;
    }
}
