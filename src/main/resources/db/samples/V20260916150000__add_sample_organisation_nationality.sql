/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- The organisation's nationality, which the product.discover policy rule requires: an organisation
-- may discover products only when its nationality is permitted (GB). Seeded for Bristol City Council
-- only, so locally BCC can discover while ENV and HEG are refused with
-- organisation.nationality_not_permitted - one permit and two denials without editing data.
--
-- Single-valued: a policy compares it as a scalar (input.subject.organisation.attributes.nationality),
-- never as an array. Stored as an ISO 3166-1 alpha-2 code to match what the rule compares against.

INSERT INTO policy_attribute_definition
    (namespace, name, display_name, description, data_type, multi_valued, validation_pattern, sensitive, created_by)
VALUES
    ('policy', 'nationality', 'Nationality',
     'The nationality the organisation operates under, as an ISO 3166-1 alpha-2 code, used to decide whether it may discover products.',
     'STRING', FALSE, '^[A-Z]{2}$', FALSE, 'sample-data');

-- Not required: an organisation without a nationality is a valid state, and is simply refused discovery.
INSERT INTO policy_attribute_definition_scope
    (attribute_definition_id, attribute_scope_id, required, created_by)
SELECT ad.id, sc.id, FALSE, 'sample-data'
FROM policy_attribute_definition ad
JOIN policy_attribute_scope sc ON sc.code = 'ORGANISATION'
WHERE ad.namespace = 'policy' AND ad.name = 'nationality';

INSERT INTO policy_attribute_value
    (attribute_definition_scope_id, entity_id, value, created_by)
SELECT ads.id, o.id, to_jsonb('GB'::text), 'sample-data'
FROM organisation o
JOIN policy_attribute_definition ad ON ad.namespace = 'policy' AND ad.name = 'nationality'
JOIN policy_attribute_scope sc ON sc.code = 'ORGANISATION'
JOIN policy_attribute_definition_scope ads
    ON ads.attribute_definition_id = ad.id AND ads.attribute_scope_id = sc.id
WHERE o.organisation_key = 'BCC';
