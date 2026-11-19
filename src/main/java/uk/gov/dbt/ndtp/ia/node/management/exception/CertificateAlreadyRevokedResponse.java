/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.exception;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * The {@link ErrorResponse} for a certificate that is already revoked, carrying the original revocation so the
 * caller can see who revoked it and when.
 */
@Getter
@Setter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class CertificateAlreadyRevokedResponse extends ErrorResponse {

    /** ISO-8601, like the successful response, whatever the ObjectMapper writes by default. */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant revokedAt;

    private String revokedBy;

    public CertificateAlreadyRevokedResponse(
            int status, String message, String errorId, Instant revokedAt, String revokedBy) {
        super(status, message, errorId);
        this.revokedAt = revokedAt;
        this.revokedBy = revokedBy;
    }
}
