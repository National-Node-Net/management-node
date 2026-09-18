/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- Pin the sample organisations to their short keys, so the scripts that follow (starting with
-- add_sample_organisation_nationality, which looks organisations up by key) can rely on ENV, BCC
-- and HEG whatever key the organisation_key backfill derived for them. Matched on the exact sample
-- name; a row with any other name is left untouched.

UPDATE organisation o
SET organisation_key = k.organisation_key
FROM (VALUES
    ('Environment Agency (ENV)', 'ENV'),
    ('Bristol City Council (BCC)', 'BCC'),
    ('Homes England (HEG)', 'HEG')
) AS k (name, organisation_key)
WHERE o.name = k.name
  AND o.organisation_key IS DISTINCT FROM k.organisation_key;
