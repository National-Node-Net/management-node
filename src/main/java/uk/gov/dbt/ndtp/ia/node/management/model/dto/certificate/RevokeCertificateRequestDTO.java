/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.RevocationReason;
import uk.gov.dbt.ndtp.ia.node.management.utils.cryptography.CertificateSerials;

/** A request to revoke one of the caller's organisation's certificates. */
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RevokeCertificateRequestDTO {

    /** Longest comment accepted; it is stored with the revocation and written to the audit log. */
    public static final int MAX_COMMENT_LENGTH = 500;

    @Schema(
            description =
                    "Serial number of the certificate, as hex bytes separated by colons or hyphens, as issued by Vault",
            example = "39:dd:2e:90:b7:23:1f:8d:d3:7d:31:c5:1b:da:84:d0:5b:65:31:58")
    @NotNull
    @Pattern(
            regexp = CertificateSerials.SEPARATED_HEX_PATTERN,
            message = "must be hex bytes separated by colons or hyphens, e.g. 39:dd:2e")
    private String serialNumber;

    @Schema(description = "Why the certificate is revoked", example = "KEY_COMPROMISE")
    @NotNull
    private RevocationReason reason;

    @Schema(description = "Optional free text, stored with the revocation and in the audit log")
    @Size(max = MAX_COMMENT_LENGTH)
    private String comment;
}
