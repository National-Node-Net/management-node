/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.entity.policy;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "policy_attribute_definition_scope")
public class PolicyAttributeDefinitionScope extends PolicyAttributeAuditFields {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attribute_definition_id", nullable = false)
    private PolicyAttributeDefinition attributeDefinition;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attribute_scope_id", nullable = false)
    private PolicyAttributeScope attributeScope;

    @NotNull
    @Column(name = "required", nullable = false)
    private Boolean required = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_value")
    private String defaultValue;
}
