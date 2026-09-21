/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- One row per live policy attribute VALUE, already joined to its definition and scope, so a query
-- can ask "does this product carry attribute X with value Y" without repeating four joins:
--
--   EXISTS (SELECT 1 FROM policy_attribute_live_value a
--           WHERE a.scope_code = 'PRODUCT' AND a.entity_id = p.id
--             AND a.name = 'identifiability' AND a.item IN ('anonymised', 'non_personal'))
--
-- "Live" means neither the value, its definition nor its scope binding is soft-deleted.
--
-- A multi-valued attribute is normally stored as one row per value, but a single row holding a
-- JSON array is also accepted (see PolicyAttributeService#findAttributeMap). Both storage
-- conventions are flattened here, so either way the view has one row per value:
--   item      - the value as text ('validated', '42', 'true')
--   item_type - its JSON type ('string', 'number', 'boolean'), for callers that need it typed
--
-- Product discovery builds its WHERE clause and loads its response attributes from this view.
CREATE VIEW policy_attribute_live_value AS
SELECT sc.code              AS scope_code,
       av.entity_id         AS entity_id,
       ad.namespace         AS namespace,
       ad.name              AS name,
       ad.multi_valued      AS multi_valued,
       ad.sensitive         AS sensitive,
       e.item #>> '{}'      AS item,
       jsonb_typeof(e.item) AS item_type
FROM policy_attribute_value av
JOIN policy_attribute_definition_scope ads ON ads.id = av.attribute_definition_scope_id
JOIN policy_attribute_definition ad ON ad.id = ads.attribute_definition_id
JOIN policy_attribute_scope sc ON sc.id = ads.attribute_scope_id
CROSS JOIN LATERAL jsonb_array_elements(
    CASE WHEN jsonb_typeof(av.value) = 'array' THEN av.value ELSE jsonb_build_array(av.value) END
) AS e (item)
WHERE av.is_deleted = FALSE
  AND ads.is_deleted = FALSE
  AND ad.is_deleted = FALSE;
