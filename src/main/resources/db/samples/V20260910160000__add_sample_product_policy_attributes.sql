/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- Sample PRODUCT-scoped policy attributes for the three sample products, describing what is actually in
-- the data - the side of the decision that says what protection a request needs, rather than what the
-- requester is entitled to.
--
-- Same storage shape as the other sample attributes: one row per value, each a JSON scalar. Only
-- population_risk_tags is multi-valued; the other four are single-select vocabularies. A product with no
-- population_risk_tags rows is asserting none are represented, which is why it is not required.

INSERT INTO policy_attribute_definition
    (namespace, name, display_name, description, data_type, multi_valued, allowed_values, sensitive, created_by)
VALUES
    ('policy', 'record_unit', 'Record unit',
     'What one record describes. Finer units such as household may need aggregating before release.',
     'STRING', FALSE,
     '["person", "household", "business", "property", "geographic_area"]'::jsonb,
     FALSE, 'sample-data'),
    ('policy', 'identifiability', 'Identifiability',
     'How identifiable the records are. Pseudonymised data still needs record-linkage and reidentification controls.',
     'STRING', FALSE,
     '["directly_identifiable", "pseudonymised", "anonymised", "non_personal"]'::jsonb,
     FALSE, 'sample-data'),
    ('policy', 'population_risk_tags', 'Population risk tags',
     'Vulnerable populations represented in the data, each requiring additional disclosure controls. No rows means none are represented.',
     'STRING', TRUE,
     '["children", "protected_witnesses", "domestic_abuse_survivors", "rare_condition_cohorts"]'::jsonb,
     TRUE, 'sample-data'),
    ('policy', 'temporal_resolution', 'Temporal resolution',
     'Finest time granularity available, so a policy can release summaries while withholding event-level observations.',
     'STRING', FALSE,
     '["event_level", "hourly", "daily", "monthly", "annual"]'::jsonb,
     FALSE, 'sample-data'),
    ('policy', 'quality_designation', 'Quality designation',
     'Maturity of the product, so a workflow needing validated evidence can exclude experimental or provisional data.',
     'STRING', FALSE,
     '["experimental", "provisional", "validated", "superseded"]'::jsonb,
     FALSE, 'sample-data');

-- Bind to the PRODUCT scope. record_unit and identifiability are required: without them there is no basis
-- for any disclosure decision at all, so their absence is a data problem rather than a deny decision.
INSERT INTO policy_attribute_definition_scope
    (attribute_definition_id, attribute_scope_id, required, created_by)
SELECT ad.id, sc.id, v.required, 'sample-data'
FROM (VALUES
    ('record_unit', TRUE),
    ('identifiability', TRUE),
    ('population_risk_tags', FALSE),
    ('temporal_resolution', FALSE),
    ('quality_designation', FALSE)
) AS v (name, required)
JOIN policy_attribute_definition ad ON ad.namespace = 'policy' AND ad.name = v.name
JOIN policy_attribute_scope sc ON sc.code = 'PRODUCT';

-- Values per product, graded from permissive to restricted so a local policy has a full range to work on:
-- FloodRiskMapZones is non-personal geography anyone can have, BrownfieldLandAvailability is pseudonymised
-- (the pairing for HEG's trusted research environment consumer), and PendingPlanningApplications is the
-- restricted one - directly identifiable, household-level, event-level, provisional, and carrying risk tags.
INSERT INTO policy_attribute_value
    (attribute_definition_scope_id, entity_id, value, created_by)
SELECT ads.id, pr.id, to_jsonb(v.value::text), 'sample-data'
FROM (VALUES
    -- Environment Agency product
    ('FloodRiskMapZones',           'record_unit',          'geographic_area'),
    ('FloodRiskMapZones',           'identifiability',      'non_personal'),
    ('FloodRiskMapZones',           'temporal_resolution',  'daily'),
    ('FloodRiskMapZones',           'quality_designation',  'validated'),

    -- Homes England product
    ('BrownfieldLandAvailability',  'record_unit',          'property'),
    ('BrownfieldLandAvailability',  'identifiability',      'pseudonymised'),
    ('BrownfieldLandAvailability',  'temporal_resolution',  'annual'),
    ('BrownfieldLandAvailability',  'quality_designation',  'validated'),

    -- Bristol City Council product
    ('PendingPlanningApplications', 'record_unit',          'household'),
    ('PendingPlanningApplications', 'identifiability',      'directly_identifiable'),
    ('PendingPlanningApplications', 'population_risk_tags', 'domestic_abuse_survivors'),
    ('PendingPlanningApplications', 'population_risk_tags', 'protected_witnesses'),
    ('PendingPlanningApplications', 'temporal_resolution',  'event_level'),
    ('PendingPlanningApplications', 'quality_designation',  'provisional')
) AS v (product, name, value)
JOIN product pr ON pr.name = v.product
JOIN policy_attribute_definition ad ON ad.namespace = 'policy' AND ad.name = v.name
JOIN policy_attribute_scope sc ON sc.code = 'PRODUCT'
JOIN policy_attribute_definition_scope ads
    ON ads.attribute_definition_id = ad.id AND ads.attribute_scope_id = sc.id;
