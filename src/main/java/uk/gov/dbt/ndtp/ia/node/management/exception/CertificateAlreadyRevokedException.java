/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception;

import java.time.Instant;
import lombok.Getter;

/**
 * A revocation request for a certificate whose record is already revoked. Answered with {@code 409} and the
 * original revocation, which is left unchanged.
 */
@Getter
public class CertificateAlreadyRevokedException extends RuntimeException {

    private final Instant revokedAt;

    private final String revokedBy;

    public CertificateAlreadyRevokedException(Instant revokedAt, String revokedBy) {
        super("Certificate is already revoked");
        this.revokedAt = revokedAt;
        this.revokedBy = revokedBy;
    }
}
