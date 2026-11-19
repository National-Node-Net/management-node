/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception;

/** The PKI backend has no certificate with the requested serial number. */
public class PkiCertificateNotFoundException extends PkiRejectedException {

    public PkiCertificateNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
