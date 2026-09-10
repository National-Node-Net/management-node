/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- Prefix the four policy-attribute tables (added by V20260902120000) with policy_, so they read as one
-- family and do not collide with the older, unrelated product_consumer_attribute table. Column names are
-- deliberately left alone: only table-derived identifiers (constraints, indexes, triggers, function) follow.

ALTER TABLE attribute_scope RENAME TO policy_attribute_scope;
ALTER TABLE attribute_definition RENAME TO policy_attribute_definition;
ALTER TABLE attribute_definition_scope RENAME TO policy_attribute_definition_scope;
ALTER TABLE attribute_value RENAME TO policy_attribute_value;

-- Constraints carry the old table name in their own names; keep them in step.
ALTER TABLE policy_attribute_scope
    RENAME CONSTRAINT uq_attribute_scope__code TO uq_policy_attribute_scope__code;

ALTER TABLE policy_attribute_definition
    RENAME CONSTRAINT uq_attribute_definition__namespace_name
        TO uq_policy_attribute_definition__namespace_name;

ALTER TABLE policy_attribute_definition_scope
    RENAME CONSTRAINT fk_attribute_definition_scope__attribute_definition_id
        TO fk_policy_attribute_definition_scope__attribute_definition_id;
ALTER TABLE policy_attribute_definition_scope
    RENAME CONSTRAINT fk_attribute_definition_scope__attribute_scope_id
        TO fk_policy_attribute_definition_scope__attribute_scope_id;
ALTER TABLE policy_attribute_definition_scope
    RENAME CONSTRAINT uq_attribute_definition_scope__definition_scope
        TO uq_policy_attribute_definition_scope__definition_scope;

ALTER TABLE policy_attribute_value
    RENAME CONSTRAINT fk_attribute_value__attribute_definition_scope_id
        TO fk_policy_attribute_value__attribute_definition_scope_id;

ALTER INDEX idx_attribute_definition_scope__attribute_definition_id
    RENAME TO idx_policy_attribute_definition_scope__attribute_definition_id;
ALTER INDEX idx_attribute_definition_scope__attribute_scope_id
    RENAME TO idx_policy_attribute_definition_scope__attribute_scope_id;
ALTER INDEX idx_attribute_value__entity_id
    RENAME TO idx_policy_attribute_value__entity_id;
ALTER INDEX uq_attr_value_live RENAME TO uq_policy_attr_value_live;

-- plpgsql resolves table names at execution time, so the trigger function has to be rebuilt against the
-- new names or every delete on an owning table would fail once the tables above are renamed.
ALTER FUNCTION fn_attribute_value_soft_delete_on_entity_delete()
    RENAME TO fn_policy_attribute_value_soft_delete_on_entity_delete;

CREATE OR REPLACE FUNCTION fn_policy_attribute_value_soft_delete_on_entity_delete() RETURNS TRIGGER AS $$
BEGIN
    UPDATE policy_attribute_value av
    SET is_deleted = TRUE,
        updated_at = now(),
        updated_by = 'trigger:' || TG_TABLE_NAME
    FROM policy_attribute_definition_scope ads
    JOIN policy_attribute_scope asc_ ON asc_.id = ads.attribute_scope_id
    WHERE av.attribute_definition_scope_id = ads.id
      AND asc_.table_name = TG_TABLE_NAME
      AND av.entity_id = OLD.id
      AND av.is_deleted = FALSE;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

ALTER TRIGGER trg_organisation_attribute_value_soft_delete ON organisation
    RENAME TO trg_organisation_policy_attribute_value_soft_delete;
ALTER TRIGGER trg_consumer_attribute_value_soft_delete ON consumer
    RENAME TO trg_consumer_policy_attribute_value_soft_delete;
ALTER TRIGGER trg_producer_attribute_value_soft_delete ON producer
    RENAME TO trg_producer_policy_attribute_value_soft_delete;
ALTER TRIGGER trg_product_attribute_value_soft_delete ON product
    RENAME TO trg_product_policy_attribute_value_soft_delete;
ALTER TRIGGER trg_product_consumer_attribute_value_soft_delete ON product_consumer
    RENAME TO trg_product_consumer_policy_attribute_value_soft_delete;
