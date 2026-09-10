/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- Sample ORGANISATION-scoped policy attributes for the three sample organisations, so the OPA data
-- bundle has something realistic to make decisions against locally.
--
-- Multi-valued attributes are stored as one row per value, each a JSON scalar - not as a single row
-- holding a JSON array. The service layer renders a value with Jackson's asText(), which yields an
-- empty string for an array node, and the partial unique index on
-- (attribute_definition_scope_id, entity_id, value) is what keeps the individual values distinct.

INSERT INTO policy_attribute_definition
    (namespace, name, display_name, description, data_type, multi_valued, allowed_values, sensitive, created_by)
VALUES
    ('policy', 'department_id', 'Department id',
     'The department the organisation acts as, used to decide whether it owns the requested information.',
     'STRING', FALSE, NULL, FALSE, 'sample-data'),
    ('policy', 'authorised_classifications', 'Authorised classifications',
     'Classifications the organisation is authorised to handle. Individual users still need their own authorisation.',
     'STRING', TRUE, '["OFFICIAL", "OFFICIAL-SENSITIVE", "SECRET", "TOP SECRET"]'::jsonb, FALSE, 'sample-data'),
    ('policy', 'responsibility_areas', 'Responsibility areas',
     'Subject areas the organisation is responsible for, used to decide whether a request falls within its remit.',
     'STRING', TRUE, NULL, FALSE, 'sample-data'),
    ('policy', 'jurisdictions', 'Jurisdictions',
     'Geographic areas the organisation has responsibility for - a nation (England, Scotland, Wales, Northern Ireland) or a local area within one.',
     'STRING', TRUE, NULL, FALSE, 'sample-data'),
    ('policy', 'permitted_purposes', 'Permitted purposes',
     'Purposes the organisation is permitted to use requested information for.',
     'STRING', TRUE, NULL, FALSE, 'sample-data');

-- Bind each definition to the ORGANISATION scope. Only department_id is required: an organisation with
-- no authorised classification simply gets no access, which is a valid state to exercise locally.
INSERT INTO policy_attribute_definition_scope
    (attribute_definition_id, attribute_scope_id, required, created_by)
SELECT ad.id, sc.id, v.required, 'sample-data'
FROM (VALUES
    ('department_id', TRUE),
    ('authorised_classifications', FALSE),
    ('responsibility_areas', FALSE),
    ('jurisdictions', FALSE),
    ('permitted_purposes', FALSE)
) AS v (name, required)
JOIN policy_attribute_definition ad ON ad.namespace = 'policy' AND ad.name = v.name
JOIN policy_attribute_scope sc ON sc.code = 'ORGANISATION';

-- Values per organisation. Deliberately uneven: BCC is OFFICIAL-only and single-purpose, ENV is the
-- only one cleared to SECRET, and HEG overlaps ENV on jurisdiction but not on remit - so a local policy
-- can produce permits and denials without editing the data. Every organisation covers England, so the
-- second jurisdiction (Wales, Bristol, Scotland) is what separates them.
INSERT INTO policy_attribute_value
    (attribute_definition_scope_id, entity_id, value, created_by)
SELECT ads.id, o.id, to_jsonb(v.value::text), 'sample-data'
FROM (VALUES
    -- Environment Agency (ENV)
    ('%ENV%', 'department_id',              'dept-environment'),
    ('%ENV%', 'authorised_classifications', 'OFFICIAL'),
    ('%ENV%', 'authorised_classifications', 'SECRET'),
    ('%ENV%', 'responsibility_areas',       'environmental_protection'),
    ('%ENV%', 'responsibility_areas',       'flood_risk_management'),
    ('%ENV%', 'jurisdictions',              'England'),
    ('%ENV%', 'jurisdictions',              'Wales'),
    ('%ENV%', 'permitted_purposes',         'service_delivery'),
    ('%ENV%', 'permitted_purposes',         'regulatory_oversight'),

    -- Bristol City Council (BCC)
    ('%BCC%', 'department_id',              'dept-local-government'),
    ('%BCC%', 'authorised_classifications', 'OFFICIAL'),
    ('%BCC%', 'responsibility_areas',       'urban_planning'),
    ('%BCC%', 'responsibility_areas',       'public_health'),
    ('%BCC%', 'jurisdictions',              'England'),
    ('%BCC%', 'jurisdictions',              'Bristol'),
    ('%BCC%', 'permitted_purposes',         'service_delivery'),

    -- Homes England (HEG)
    ('%HEG%', 'department_id',              'dept-housing'),
    ('%HEG%', 'authorised_classifications', 'OFFICIAL'),
    ('%HEG%', 'authorised_classifications', 'OFFICIAL-SENSITIVE'),
    ('%HEG%', 'responsibility_areas',       'housing_delivery'),
    ('%HEG%', 'responsibility_areas',       'land_availability'),
    ('%HEG%', 'jurisdictions',              'England'),
    ('%HEG%', 'jurisdictions',              'Scotland'),
    ('%HEG%', 'permitted_purposes',         'service_delivery'),
    ('%HEG%', 'permitted_purposes',         'statistical_analysis')
) AS v (org, name, value)
JOIN organisation o ON o.name LIKE v.org
JOIN policy_attribute_definition ad ON ad.namespace = 'policy' AND ad.name = v.name
JOIN policy_attribute_scope sc ON sc.code = 'ORGANISATION'
JOIN policy_attribute_definition_scope ads
    ON ads.attribute_definition_id = ad.id AND ads.attribute_scope_id = sc.id;
