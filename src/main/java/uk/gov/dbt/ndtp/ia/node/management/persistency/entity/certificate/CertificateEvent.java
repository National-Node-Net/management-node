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

@Getter
@Setter
@Entity
@Table(name = "certificate_events")
public class CertificateEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organisation_certificate_id", nullable = false)
    private OrganisationCertificate organisationCertificate;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    private CertificateType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private CertificateEventType eventType;

    @Column(name = "event_time", nullable = false)
    private Timestamp eventTime;

    @Column(name = "performed_by", length = 255)
    private String performedBy;
}
