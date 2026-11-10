/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

-- Rollback for db/migration/V20261109120000__add_certificate_revocation.sql.
--
-- Flyway Community has no undo migrations, and this directory is not on spring.flyway.locations, so this
-- script never runs on its own. Run it by hand against the mn schema, in one transaction, with the
-- application stopped. It discards who revoked each certificate and why; revoked_at is kept.

BEGIN;

DROP INDEX IF EXISTS idx_organisation_certificate__serial_number;

ALTER TABLE organisation_certificate DROP COLUMN IF EXISTS revocation_message;
ALTER TABLE organisation_certificate DROP COLUMN IF EXISTS revocation_reason;
ALTER TABLE organisation_certificate DROP COLUMN IF EXISTS revoked_by;

ALTER TABLE organisation_certificate
    ALTER COLUMN revoked_at TYPE TIMESTAMP USING revoked_at AT TIME ZONE 'UTC';

COMMENT ON COLUMN organisation_certificate.revoked_at IS NULL;

-- Lets Flyway apply the migration again.
DELETE FROM flyway_schema_history WHERE version = '20261109120000';

COMMIT;
