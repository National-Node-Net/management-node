/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- A human-readable description of the data product. It is what discovery free-text search
-- (POST /api/v1/product/discover, "text") matches against, together with the product name.
-- Nullable: existing products have none until their owners provide one.
ALTER TABLE product ADD COLUMN description TEXT;

COMMENT ON COLUMN product.description IS
    'Human-readable description of the data product; searched by discovery free text.';
