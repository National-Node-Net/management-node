/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- The name a user interface shows for the product. `name` stays the stable identifier; this is
-- prose a data owner may change. Discovery free-text search matches it alongside name and
-- description. Nullable: existing products have none until their owners provide one.
ALTER TABLE product ADD COLUMN display_name VARCHAR(150);

-- The format the product's data is published in, e.g. JSON, XML, CSV, RDF. Deliberately free text
-- rather than a lookup table or a CHECK: the set of formats is open and is not ours to close.
ALTER TABLE product ADD COLUMN format VARCHAR(100);

COMMENT ON COLUMN product.display_name IS
    'Name shown to users in place of product.name; searched by discovery free text.';

COMMENT ON COLUMN product.format IS
    'Format the product''s data is published in (e.g. JSON, XML, CSV, RDF); open-ended.';
