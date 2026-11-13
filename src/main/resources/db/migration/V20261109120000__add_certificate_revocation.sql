/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

-- Who revoked an organisation's certificate, when and why, recorded by POST /api/v1/certificate/revoke.
-- Rollback: db/rollback/V20261109120000__add_certificate_revocation.rollback.sql (run by hand).

-- revoked_at holds Vault's revocation_time, an instant, so it carries its time zone. Nothing has written
-- the column before this migration, so existing values are expected to be NULL; any that exist are taken
-- as UTC.
ALTER TABLE organisation_certificate
    ALTER COLUMN revoked_at TYPE TIMESTAMP WITH TIME ZONE USING revoked_at AT TIME ZONE 'UTC';

-- NULL while the certificate is active, which is what every existing row is.
ALTER TABLE organisation_certificate ADD COLUMN revoked_by VARCHAR(255);
ALTER TABLE organisation_certificate ADD COLUMN revocation_reason VARCHAR(50);
ALTER TABLE organisation_certificate ADD COLUMN revocation_message TEXT;

-- The revocation API and the OCSP responder look certificates up by serial number.
CREATE INDEX idx_organisation_certificate__serial_number ON organisation_certificate (serial_number);

COMMENT ON COLUMN organisation_certificate.revoked_at IS
    'Revocation time returned by Vault (revocation_time); NULL while the certificate is active.';

COMMENT ON COLUMN organisation_certificate.revoked_by IS
    'Keycloak user id (sub claim) of the administrator who revoked the certificate.';

COMMENT ON COLUMN organisation_certificate.revocation_reason IS
    'RFC 5280 reason given for the revocation, e.g. KEY_COMPROMISE; returned in OCSP responses.';

COMMENT ON COLUMN organisation_certificate.revocation_message IS
    'Free-text comment given for the revocation.';
