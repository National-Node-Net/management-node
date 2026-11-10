/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.RevocationReason;

/** The revocation recorded for a certificate. */
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RevokeCertificateResponseDTO {

    /** The only status a successful revocation reports. */
    public static final String STATUS_REVOKED = "REVOKED";

    @Schema(
            description = "Serial number, in Vault's colon-separated form",
            example = "39:dd:2e:90:b7:23:1f:8d:d3:7d:31:c5:1b:da:84:d0:5b:65:31:58")
    private String serialNumber;

    @Schema(example = STATUS_REVOKED)
    private String status;

    @Schema(example = "KEY_COMPROMISE")
    private RevocationReason reason;

    @Schema(description = "Revocation time recorded by Vault", example = "2026-10-12T13:15:02Z")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant revokedAt;

    @Schema(description = "Keycloak user id (sub claim) of the administrator", example = "a1b2c3d4-keycloak-user-id")
    private String revokedBy;
}
