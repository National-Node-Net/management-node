/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "attribute_value")
public class AttributeValue extends AttributeAuditFields {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attribute_definition_scope_id", nullable = false)
    private AttributeDefinitionScope attributeDefinitionScope;

    /**
     * Polymorphic reference: the primary key of the row in the table named by
     * {@code attributeDefinitionScope.attributeScope.tableName}. Not a JPA relationship
     * because the target entity type varies by scope; see the migration's soft-delete
     * trigger for how this is enforced at the database level.
     */
    @NotNull
    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value", nullable = false)
    private String value;
}
