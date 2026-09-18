/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration;

import jakarta.persistence.*;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.organisation.Organisation;

@Getter
@Setter
@Entity
@Table(name = "consumer")
public class Consumer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "schedule_type", nullable = false)
    private String scheduleType;

    @Column(name = "schedule_expression")
    private String scheduleExpression;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "org_id", nullable = false)
    private Organisation org;

    @Column(name = "idp_client_id", nullable = false, length = 50)
    private String idpClientId;

    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "consumer_id", referencedColumnName = "id", insertable = false, updatable = false)
    private List<ProductConsumer> productConsumers;
}
