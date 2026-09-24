/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- Which consumer a subscription belongs to when the caller does not name one.
--
-- The column is is_default rather than "default": DEFAULT is a reserved word in SQL, so a column
-- of that name would have to be quoted in every statement that touched it, and an unquoted
-- reference would be a syntax error rather than a missing-column error.
--
-- Nullable is not wanted here: "not the default" and "not yet decided" are the same thing, so the
-- column is NOT NULL with a FALSE default and existing consumers become non-default.
ALTER TABLE consumer ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN consumer.is_default IS
    'Whether this consumer receives subscriptions its organisation requests without naming a consumer.';

-- An organisation has at most one default consumer, and may have none. A partial unique index says
-- exactly that: it constrains only the rows where is_default is TRUE, so any number of
-- non-default consumers per organisation stays legal.
CREATE UNIQUE INDEX uq_consumer__one_default_per_org
    ON consumer (org_id)
    WHERE is_default = TRUE;
