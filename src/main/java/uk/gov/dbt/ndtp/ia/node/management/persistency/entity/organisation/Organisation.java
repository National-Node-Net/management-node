/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.entity.organisation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "organisation")
public class Organisation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    /**
     * Stable, human-readable identifier for the organisation (e.g. {@code ORG_A}), unique across
     * organisations and indexed, so callers can address an organisation without knowing its id.
     */
    @Column(name = "organisation_key", nullable = false, unique = true, length = 50)
    private String organisationKey;

    @Column(name = "certificate_automation_enabled", nullable = false)
    private Boolean certificateAutomationEnabled = true;
}
