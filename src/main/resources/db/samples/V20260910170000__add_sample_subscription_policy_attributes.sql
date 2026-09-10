/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- Sample SUBSCRIPTION-scoped policy attributes for the three sample product/consumer grants. These are the
-- terms agreed for one specific pairing - what this consumer may do with this product - as opposed to the
-- standing facts held on the organisation, consumer, producer and product.
--
-- Same storage shape as the other sample attributes: one row per value, each a JSON scalar. Only
-- permitted_operations is multi-valued. retention_period has no allowed_values because ISO 8601 durations
-- are an open vocabulary; it carries a validation_pattern instead, which is advisory metadata today - no
-- code enforces it yet.

INSERT INTO policy_attribute_definition
    (namespace, name, display_name, description, data_type, multi_valued, allowed_values, validation_pattern, sensitive, created_by)
VALUES
    ('policy', 'approved_use_case', 'Approved use case',
     'The single use the grant was approved for. A request for anything else is outside the agreement.',
     'STRING', FALSE,
     '["verify_benefit_eligibility", "forecast_school_places", "coordinate_flood_evacuation", "evaluate_employment_programme"]'::jsonb,
     NULL, FALSE, 'sample-data'),
    ('policy', 'permitted_operations', 'Permitted operations',
     'Operations the grant allows, enumerated individually - holding query does not imply download, link_records or train_model.',
     'STRING', TRUE,
     '["query", "download", "aggregate", "link_records", "train_model"]'::jsonb,
     NULL, FALSE, 'sample-data'),
    ('policy', 'retention_period', 'Retention period',
     'How long a received extract may be kept before deletion, as an ISO 8601 duration (e.g. P7D, P90D, P1Y).',
     'STRING', FALSE, NULL,
     '^P([0-9]+Y)?([0-9]+M)?([0-9]+W)?([0-9]+D)?(T([0-9]+H)?([0-9]+M)?([0-9]+S)?)?$',
     FALSE, 'sample-data'),
    ('policy', 'onward_sharing_rule', 'Onward sharing rule',
     'Whether and how what is received may be passed on, used to block redistribution of raw records.',
     'STRING', FALSE,
     '["recipient_only", "named_partners_only", "approval_required", "aggregate_publication_only"]'::jsonb,
     NULL, FALSE, 'sample-data'),
    ('policy', 'output_release_control', 'Output release control',
     'What has to happen before analytical outputs may leave the consumer''s processing environment.',
     'STRING', FALSE,
     '["no_export", "automated_disclosure_check", "manual_output_review", "unrestricted_export"]'::jsonb,
     NULL, FALSE, 'sample-data');

-- Bind to the SUBSCRIPTION scope. approved_use_case and permitted_operations are required: a grant that
-- names neither a purpose nor an operation does not describe an agreement at all.
INSERT INTO policy_attribute_definition_scope
    (attribute_definition_id, attribute_scope_id, required, created_by)
SELECT ad.id, sc.id, v.required, 'sample-data'
FROM (VALUES
    ('approved_use_case', TRUE),
    ('permitted_operations', TRUE),
    ('retention_period', FALSE),
    ('onward_sharing_rule', FALSE),
    ('output_release_control', FALSE)
) AS v (name, required)
JOIN policy_attribute_definition ad ON ad.namespace = 'policy' AND ad.name = v.name
JOIN policy_attribute_scope sc ON sc.code = 'SUBSCRIPTION';

-- Values per grant, each matched to what the two sides already say elsewhere:
--   FloodRiskMapZones -> BCC: non-personal data to an emergency_response consumer, so broad operations,
--     unrestricted export, and a short P7D retention because it is operational incident data.
--   PendingPlanningApplications -> HEG: directly identifiable, risk-tagged data into a trusted research
--     environment - query and aggregate only, no download, manual output review, publish aggregates only.
--   BrownfieldLandAvailability -> ENV: pseudonymised data to a fully_automated consumer, and the only
--     grant permitting link_records and train_model - the combination a reidentification or automated-
--     decision rule is meant to catch.
INSERT INTO policy_attribute_value
    (attribute_definition_scope_id, entity_id, value, created_by)
SELECT ads.id, pc.id, to_jsonb(v.value::text), 'sample-data'
FROM (VALUES
    -- FloodRiskMapZones -> BCC-CONSUMER-1
    ('FloodRiskMapZones',           'BCC-CONSUMER-1', 'approved_use_case',      'coordinate_flood_evacuation'),
    ('FloodRiskMapZones',           'BCC-CONSUMER-1', 'permitted_operations',   'query'),
    ('FloodRiskMapZones',           'BCC-CONSUMER-1', 'permitted_operations',   'download'),
    ('FloodRiskMapZones',           'BCC-CONSUMER-1', 'permitted_operations',   'aggregate'),
    ('FloodRiskMapZones',           'BCC-CONSUMER-1', 'retention_period',       'P7D'),
    ('FloodRiskMapZones',           'BCC-CONSUMER-1', 'onward_sharing_rule',    'named_partners_only'),
    ('FloodRiskMapZones',           'BCC-CONSUMER-1', 'output_release_control', 'unrestricted_export'),

    -- PendingPlanningApplications -> HEG-CONSUMER-1
    ('PendingPlanningApplications', 'HEG-CONSUMER-1', 'approved_use_case',      'forecast_school_places'),
    ('PendingPlanningApplications', 'HEG-CONSUMER-1', 'permitted_operations',   'query'),
    ('PendingPlanningApplications', 'HEG-CONSUMER-1', 'permitted_operations',   'aggregate'),
    ('PendingPlanningApplications', 'HEG-CONSUMER-1', 'retention_period',       'P90D'),
    ('PendingPlanningApplications', 'HEG-CONSUMER-1', 'onward_sharing_rule',    'aggregate_publication_only'),
    ('PendingPlanningApplications', 'HEG-CONSUMER-1', 'output_release_control', 'manual_output_review'),

    -- BrownfieldLandAvailability -> ENV-CONSUMER-1
    ('BrownfieldLandAvailability',  'ENV-CONSUMER-1', 'approved_use_case',      'evaluate_employment_programme'),
    ('BrownfieldLandAvailability',  'ENV-CONSUMER-1', 'permitted_operations',   'query'),
    ('BrownfieldLandAvailability',  'ENV-CONSUMER-1', 'permitted_operations',   'download'),
    ('BrownfieldLandAvailability',  'ENV-CONSUMER-1', 'permitted_operations',   'link_records'),
    ('BrownfieldLandAvailability',  'ENV-CONSUMER-1', 'permitted_operations',   'train_model'),
    ('BrownfieldLandAvailability',  'ENV-CONSUMER-1', 'retention_period',       'P1Y'),
    ('BrownfieldLandAvailability',  'ENV-CONSUMER-1', 'onward_sharing_rule',    'recipient_only'),
    ('BrownfieldLandAvailability',  'ENV-CONSUMER-1', 'output_release_control', 'automated_disclosure_check')
) AS v (product, consumer, name, value)
JOIN product pr ON pr.name = v.product
JOIN consumer c ON c.name = v.consumer
JOIN product_consumer pc ON pc.product_id = pr.id AND pc.consumer_id = c.id
JOIN policy_attribute_definition ad ON ad.namespace = 'policy' AND ad.name = v.name
JOIN policy_attribute_scope sc ON sc.code = 'SUBSCRIPTION'
JOIN policy_attribute_definition_scope ads
    ON ads.attribute_definition_id = ad.id AND ads.attribute_scope_id = sc.id;
