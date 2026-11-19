/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate;

import java.sql.Timestamp;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateType;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.RevocationReason;

/**
 * DTO for OrganisationCertificate entity.
 */
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrganisationCertificateDTO {

    private Long id;

    private Long organisationId;

    private Boolean certificateAutomationEnabled;

    private String subjectDn;

    private String serialNumber;

    private Boolean isRenewable;

    private Long renewalTtl;

    private CertificateType type;

    private Timestamp requestedAt;

    private Timestamp issuedAt;

    private Timestamp expiresAt;

    private Timestamp revokedAt;

    private String revokedBy;

    private RevocationReason revocationReason;

    private String revocationMessage;

    /** Clears the revocation, for a record that now holds a newly issued certificate. */
    public void clearRevocation() {
        this.revokedAt = null;
        this.revokedBy = null;
        this.revocationReason = null;
        this.revocationMessage = null;
    }
}
