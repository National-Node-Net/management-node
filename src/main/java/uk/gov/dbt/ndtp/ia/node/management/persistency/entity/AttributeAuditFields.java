/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import lombok.Getter;
import lombok.Setter;

/**
 * Shared soft-delete and audit columns for the policy attribute schema entities
 * ({@link AttributeDefinition}, {@link AttributeDefinitionScope}, {@link AttributeValue}).
 */
@Getter
@Setter
@MappedSuperclass
public abstract class AttributeAuditFields {

    @NotNull
    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false;

    @NotNull
    @Column(name = "created_at", nullable = false)
    private Timestamp createdAt;

    @Size(max = 255)
    @NotNull
    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    @Size(max = 255)
    @Column(name = "updated_by", length = 255)
    private String updatedBy;
}
