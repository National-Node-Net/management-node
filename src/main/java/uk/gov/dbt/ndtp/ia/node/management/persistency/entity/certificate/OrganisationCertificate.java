/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate;

import jakarta.persistence.*;
import java.sql.Timestamp;
import lombok.Getter;
import lombok.Setter;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.organisation.Organisation;

@Getter
@Setter
@Entity
@Table(name = "organisation_certificate")
public class OrganisationCertificate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organisation_id", unique = true)
    private Organisation organisation;

    @Column(name = "subject_dn", length = 500)
    private String subjectDn;

    @Column(name = "serial_number", length = 150)
    private String serialNumber;

    @Column(name = "is_renewable", nullable = false)
    private Boolean isRenewable = false;

    @Column(name = "renewal_ttl")
    private Long renewalTtl;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    private CertificateType type;

    @Column(name = "requested_at")
    private Timestamp requestedAt;

    @Column(name = "issued_at")
    private Timestamp issuedAt;

    @Column(name = "expires_at")
    private Timestamp expiresAt;

    @Column(name = "revoked_at")
    private Timestamp revokedAt;
}
