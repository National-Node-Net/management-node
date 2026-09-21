/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- An organisation's classifications and purposes describe its own clearance, so other
-- organisations should not read them off a discovery result. Discovery withholds every attribute
-- whose definition is flagged sensitive when the policy says so (mask_sensitive_attributes).
UPDATE policy_attribute_definition
SET sensitive = TRUE, updated_at = now(), updated_by = 'sample-data'
WHERE namespace = 'policy' AND name IN ('authorised_classifications', 'permitted_purposes');
0