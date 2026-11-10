/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.RevocationReason;

/**
 * Revocation status of a single certificate, as reported by a certificate status provider for the OCSP responder.
 */
@Builder
@Getter
@Setter
@EqualsAndHashCode
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class CertificateStatusDTO {

    /** Status of a certificate. */
    public enum Status {
        GOOD,
        REVOKED,
        UNKNOWN
    }

    private Status status;
    private Instant revokedAt;

    /** Why the certificate was revoked; null when no reason is known. */
    private RevocationReason revocationReason;

    public static CertificateStatusDTO good() {
        return CertificateStatusDTO.builder().status(Status.GOOD).build();
    }

    public static CertificateStatusDTO revoked(Instant revokedAt) {
        return revoked(revokedAt, null);
    }

    public static CertificateStatusDTO revoked(Instant revokedAt, RevocationReason revocationReason) {
        return CertificateStatusDTO.builder()
                .status(Status.REVOKED)
                .revokedAt(revokedAt)
                .revocationReason(revocationReason)
                .build();
    }

    public static CertificateStatusDTO unknown() {
        return CertificateStatusDTO.builder().status(Status.UNKNOWN).build();
    }
}
