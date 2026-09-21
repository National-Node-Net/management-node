/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- Indexes for the joins product discovery makes on every search.
--
-- The search reaches the owning organisation through product -> producer -> organisation, and a
-- filter on who is using a product (accessedBy) probes product_consumer by product. Both are
-- foreign key columns, which PostgreSQL does not index automatically.
--
-- The attribute probes are already served: policy_attribute_value has
-- idx_policy_attribute_value__entity_id and the partial unique index on
-- (attribute_definition_scope_id, entity_id, value) WHERE NOT is_deleted.
--
-- Free-text search is a leading-wildcard LIKE on name and description, which no b-tree can serve.
-- If EXPLAIN shows it matters on a realistic catalogue, the answer is a pg_trgm GIN index -- but
-- CREATE EXTENSION needs a privileged role, so that is agreed with the database owner rather than
-- assumed here.
CREATE INDEX IF NOT EXISTS idx_product__producer_id ON product (producer_id);

CREATE INDEX IF NOT EXISTS idx_producer__org_id ON producer (org_id);

CREATE INDEX IF NOT EXISTS idx_product_consumer__product_id ON product_consumer (product_id);

CREATE INDEX IF NOT EXISTS idx_consumer__org_id ON consumer (org_id);
