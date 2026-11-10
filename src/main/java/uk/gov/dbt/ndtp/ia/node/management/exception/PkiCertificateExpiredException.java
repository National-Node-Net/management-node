/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception;

/**
 * The PKI backend accepted a revocation but recorded nothing, because the certificate has already expired. An
 * expired certificate is rejected by every client regardless, so there is nothing to revoke.
 */
public class PkiCertificateExpiredException extends PkiException {

    public PkiCertificateExpiredException(String message) {
        super(message);
    }
}
