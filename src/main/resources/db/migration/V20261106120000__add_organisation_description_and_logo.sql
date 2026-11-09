/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- Shown on a product's details page. The logo is a URL to an image hosted elsewhere.
ALTER TABLE organisation ADD COLUMN description TEXT;
ALTER TABLE organisation ADD COLUMN logo_url VARCHAR(2048);

COMMENT ON COLUMN organisation.description IS
    'Description of the organisation, shown on the details page of each product it offers.';

COMMENT ON COLUMN organisation.logo_url IS
    'Absolute URL of the organisation''s logo image, hosted elsewhere and loaded by the user interface.';
