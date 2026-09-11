/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- A stable, human-readable key for an organisation, for callers that should not have to know
-- database ids: ENV, BCC, HEG. Unique and indexed, so it can be used as a lookup key.

ALTER TABLE organisation ADD COLUMN organisation_key VARCHAR(50);

-- Backfill from the existing name. The sample organisations carry their key in a trailing
-- parenthesised code ("Environment Agency (ENV)" -> ENV); anything else falls back to an
-- upper-snake-case slug of the name, so a real deployment's rows get a usable key too.
UPDATE organisation
SET organisation_key = left(
    upper(coalesce(
        substring(name from '\(([A-Za-z0-9_-]+)\)\s*$'),
        regexp_replace(btrim(name), '[^A-Za-z0-9]+', '_', 'g'))),
    45);

-- Two organisations whose names slug to the same key would break the unique index below, so
-- disambiguate the later row(s) by id rather than failing the migration.
UPDATE organisation o
SET organisation_key = o.organisation_key || '_' || o.id
WHERE EXISTS (
    SELECT 1 FROM organisation earlier
    WHERE earlier.organisation_key = o.organisation_key
      AND earlier.id < o.id);

-- A row with a blank name would have slugged to an empty string; give it something addressable.
UPDATE organisation SET organisation_key = 'ORG_' || id WHERE coalesce(organisation_key, '') = '';

ALTER TABLE organisation ALTER COLUMN organisation_key SET NOT NULL;
CREATE UNIQUE INDEX uq_organisation__organisation_key ON organisation (organisation_key);
