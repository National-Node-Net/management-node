/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- Local-only seed data so both consumer-resolution paths of POST /api/v1/product/subscribe can be
-- exercised without editing the database by hand.
--
-- Each sample organisation runs two consumers. Naming one of ENV's as the default splits them:
--
--   ENV  has a default, so a subscribe request that names no consumer succeeds
--   HEG  has two consumers and no default, so the same request is refused and asks for one
--   BCC  likewise
--
-- Leaving HEG and BCC without a default is deliberate: the refusal is as much a behaviour worth
-- testing as the success, and it needs an organisation that exhibits it.
UPDATE consumer
SET is_default = TRUE
WHERE name = 'ENV-CONSUMER-1'
  AND org_id = (SELECT id FROM organisation WHERE organisation_key = 'ENV');
