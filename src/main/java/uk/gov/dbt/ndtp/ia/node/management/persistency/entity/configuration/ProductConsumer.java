/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "product_consumer")
public class ProductConsumer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "granted_ts", nullable = false)
    private Timestamp grantedTs;

    @Column(name = "validity", nullable = false)
    private BigDecimal validity;

    @Column(name = "schedule_type", nullable = false)
    private String scheduleType;

    @Column(name = "schedule_expression")
    private String scheduleExpression;

    @Column(name = "destination")
    private String destination;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "consumer_id", nullable = false)
    private Consumer consumer;

    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_consumer_id", referencedColumnName = "id", insertable = false, updatable = false)
    private List<ProductConsumerAttribute> productConsumerAttributes;
}
