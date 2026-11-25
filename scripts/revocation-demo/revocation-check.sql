--
-- SPDX-License-Identifier: Apache-2.0
-- © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
-- attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
--

-- Revocation demo: what Management Node stored about the ENV organisation's certificate.
-- Connection: localhost:5433, database keycloak_db, schema mn, user and password from docker/keycloak/.env
-- (POSTGRES_USER / POSTGRES_PASSWORD).

-- 1. The organisation's current certificate.
--    After "1.2 Sign CSR": new serial_number, revoked_* columns empty.
--    After "3.2 Revoke certificate": revoked_at, revoked_by (sub of the token), revocation_reason, revocation_message.
SELECT o.organisation_key,
       oc.serial_number,
       oc.type,
       oc.issued_at,
       oc.expires_at,
       oc.revoked_at,
       oc.revoked_by,
       oc.revocation_reason,
       oc.revocation_message
FROM mn.organisation_certificate oc
JOIN mn.organisation o ON o.id = oc.organisation_id
WHERE o.organisation_key = 'ENV';

-- 2. The certificate's history: RENEWED (signing), then REVOKED.
--    event_time is local time (no time zone); revoked_at above is UTC.
SELECT e.id,
       e.type,
       e.event_type,
       e.event_time,
       e.performed_by
FROM mn.certificate_events e
JOIN mn.organisation_certificate oc ON oc.id = e.organisation_certificate_id
JOIN mn.organisation o ON o.id = oc.organisation_id
WHERE o.organisation_key = 'ENV'
ORDER BY e.event_time DESC
LIMIT 5;
