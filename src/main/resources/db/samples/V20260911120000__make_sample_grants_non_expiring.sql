/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- The sample grants in V20250728152300 were written with a fixed granted_ts of 2025-07-01 and a
-- validity of 365 days, so they silently expired on 2026-07-01: ConfigurationProviderImpl drops any
-- product_consumer row where granted_ts + validity days is in the past, which empties both
-- `consumers` and `configurations` on GET /api/v1/configuration/producer.
--
-- validity = 0 means "no expiry" to isValidProvider, so grants stay usable indefinitely rather than
-- rotting a year after whoever wrote the fixed date.
--
-- This applies to every product_consumer row, not just the pairings seeded by V20250728152300 - so a
-- grant added by hand while working locally does not expire either. It lives in db/samples, which is
-- on the Flyway path for local and dev profiles only (see spring.flyway.locations); it must not be
-- promoted to db/migration, where it would clear the expiry on real grants.
UPDATE product_consumer
SET validity = 0
WHERE validity IS DISTINCT FROM 0;
