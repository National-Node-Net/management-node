/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- Removes the soft-delete trigger function and the five triggers that call it.
--
-- The function resolved policy_attribute_value, policy_attribute_definition_scope and
-- policy_attribute_scope unqualified, and PL/pgSQL resolves unqualified names against the
-- *caller's* search_path rather than the schema the function lives in. A session without the
-- schema on its search_path therefore could not delete from any owning table at all:
--
--     ERROR: relation "policy_attribute_value" does not exist
--     Where: PL/pgSQL function fn_policy_attribute_value_soft_delete_on_entity_delete() line 3
--
-- The triggers are dropped explicitly rather than relying on DROP FUNCTION ... CASCADE, so this
-- file records exactly what is being removed instead of leaving it to be inferred.
--
-- CONSEQUENCE, recorded deliberately. policy_attribute_value.entity_id is polymorphic: one column
-- referencing whichever table the value's scope names, so it has no foreign key and no cascade.
-- These triggers were the only thing marking an entity's attribute values is_deleted when the
-- entity was removed. Without them, deleting an organisation, consumer, producer, product or
-- subscription leaves its attribute rows live, and those rows:
--
--   * remain visible through the policy_attribute_live_value view;
--   * are still matched by entity_id, so they can reach a policy decision for an entity that no
--     longer exists;
--   * would attach to a different entity if an id were ever reused by a restore or an import
--     that sets ids explicitly.
--
-- Whatever deletes these entities from now on is responsible for marking their attribute values
-- is_deleted in the same transaction.
DROP TRIGGER IF EXISTS trg_organisation_policy_attribute_value_soft_delete ON organisation;
DROP TRIGGER IF EXISTS trg_consumer_policy_attribute_value_soft_delete ON consumer;
DROP TRIGGER IF EXISTS trg_producer_policy_attribute_value_soft_delete ON producer;
DROP TRIGGER IF EXISTS trg_product_policy_attribute_value_soft_delete ON product;
DROP TRIGGER IF EXISTS trg_product_consumer_policy_attribute_value_soft_delete ON product_consumer;

DROP FUNCTION IF EXISTS fn_policy_attribute_value_soft_delete_on_entity_delete();
