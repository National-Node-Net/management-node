/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- The full account of a product, shown on its details page. `description` stays the short one a
-- search result shows; this is everything else a data owner wants to say. Discovery free-text
-- search matches both. Nullable: existing products have none until their owners provide one.
ALTER TABLE product ADD COLUMN long_description TEXT;

COMMENT ON COLUMN product.description IS
    'Short description of the data product, shown in search results; searched by discovery free text.';

COMMENT ON COLUMN product.long_description IS
    'Long description of the data product, shown on its details page; searched by discovery free text.';
