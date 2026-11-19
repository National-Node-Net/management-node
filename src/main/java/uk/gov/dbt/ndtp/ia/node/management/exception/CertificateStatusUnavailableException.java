/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception;

/**
 * Thrown when the certificate status backend (e.g. Vault) cannot be reached or returns an error.
 * The OCSP responder maps it to the {@code tryLater} response status.
 */
public class CertificateStatusUnavailableException extends RuntimeException {

    public CertificateStatusUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
