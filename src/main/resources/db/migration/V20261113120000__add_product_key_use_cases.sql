/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- Shown on a product's details page, in order.
ALTER TABLE product ADD COLUMN key_use_cases TEXT[];

COMMENT ON COLUMN product.key_use_cases IS 'Key use cases of the data product, in display order, shown on its details page.';
