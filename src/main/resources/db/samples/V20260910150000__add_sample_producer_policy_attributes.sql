/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- Sample PRODUCER-scoped policy attributes for the three sample producers, describing the terms the
-- publishing side sets - who it will admit, how a release is approved, and what happens in an incident.
-- With the ORGANISATION and CONSUMER samples this completes the three sides of a subscription decision.
--
-- Same storage shape as the other sample attributes: one row per value, each a JSON scalar. None of the
-- five is multi-valued - each is a single-select vocabulary - so every definition carries its full option
-- list in allowed_values even where no sample producer uses every option.

INSERT INTO policy_attribute_definition
    (namespace, name, display_name, description, data_type, multi_valued, allowed_values, sensitive, created_by)
VALUES
    ('policy', 'publication_capacity', 'Publication capacity',
     'The capacity the producer publishes in. A delegated_publisher needs a delegation covering the product.',
     'STRING', FALSE,
     '["originating_authority", "delegated_publisher", "cross_agency_aggregator", "archive_custodian"]'::jsonb,
     FALSE, 'sample-data'),
    ('policy', 'release_approval_route', 'Release approval route',
     'The approval a subscription request has to clear before the producer will release data.',
     'STRING', FALSE,
     '["standing_authorisation", "data_steward_signoff", "disclosure_panel", "joint_controller_signoff"]'::jsonb,
     FALSE, 'sample-data'),
    ('policy', 'recipient_admission_model', 'Recipient admission model',
     'The rule a consumer has to satisfy to be admitted, checked against the consumer''s own attributes.',
     'STRING', FALSE,
     '["public_sector_membership", "named_organisation_allowlist", "accredited_research_network", "bilateral_agreement"]'::jsonb,
     FALSE, 'sample-data'),
    ('policy', 'disclosure_review_frequency', 'Disclosure review frequency',
     'How often disclosure review is required. A release is held when the review it depends on is overdue.',
     'STRING', FALSE,
     '["each_release", "monthly", "quarterly", "on_material_change"]'::jsonb,
     FALSE, 'sample-data'),
    ('policy', 'emergency_release_protocol', 'Emergency release protocol',
     'The route available during a verified incident, or no_exception_route where the normal process always stands.',
     'STRING', FALSE,
     '["no_exception_route", "incident_commander_approval", "two_person_authorisation", "preapproved_emergency_cohort"]'::jsonb,
     FALSE, 'sample-data');

-- Bind to the PRODUCER scope. publication_capacity and release_approval_route are required: without them
-- a request cannot be routed for approval at all, which is a data problem rather than a deny decision.
INSERT INTO policy_attribute_definition_scope
    (attribute_definition_id, attribute_scope_id, required, created_by)
SELECT ad.id, sc.id, v.required, 'sample-data'
FROM (VALUES
    ('publication_capacity', TRUE),
    ('release_approval_route', TRUE),
    ('recipient_admission_model', FALSE),
    ('disclosure_review_frequency', FALSE),
    ('emergency_release_protocol', FALSE)
) AS v (name, required)
JOIN policy_attribute_definition ad ON ad.namespace = 'policy' AND ad.name = v.name
JOIN policy_attribute_scope sc ON sc.code = 'PRODUCER';

-- Values per producer: no two producers share a value on any attribute, so each attribute on its own
-- picks out exactly one producer. Chosen to line up with the CONSUMER samples - HEG admits only an
-- accredited_research_network, which HEG's consumer satisfies and BCC's consumer does not, and ENV's
-- incident_commander_approval is the counterpart to BCC's emergency_response consumer.
INSERT INTO policy_attribute_value
    (attribute_definition_scope_id, entity_id, value, created_by)
SELECT ads.id, p.id, to_jsonb(v.value::text), 'sample-data'
FROM (VALUES
    -- Environment Agency producer
    ('ENV-PRODUCER-1', 'publication_capacity',        'originating_authority'),
    ('ENV-PRODUCER-1', 'release_approval_route',      'standing_authorisation'),
    ('ENV-PRODUCER-1', 'recipient_admission_model',   'public_sector_membership'),
    ('ENV-PRODUCER-1', 'disclosure_review_frequency', 'each_release'),
    ('ENV-PRODUCER-1', 'emergency_release_protocol',  'incident_commander_approval'),

    -- Bristol City Council producer
    ('BCC-PRODUCER-1', 'publication_capacity',        'delegated_publisher'),
    ('BCC-PRODUCER-1', 'release_approval_route',      'data_steward_signoff'),
    ('BCC-PRODUCER-1', 'recipient_admission_model',   'named_organisation_allowlist'),
    ('BCC-PRODUCER-1', 'disclosure_review_frequency', 'quarterly'),
    ('BCC-PRODUCER-1', 'emergency_release_protocol',  'two_person_authorisation'),

    -- Homes England producer
    ('HEG-PRODUCER-1', 'publication_capacity',        'cross_agency_aggregator'),
    ('HEG-PRODUCER-1', 'release_approval_route',      'disclosure_panel'),
    ('HEG-PRODUCER-1', 'recipient_admission_model',   'accredited_research_network'),
    ('HEG-PRODUCER-1', 'disclosure_review_frequency', 'on_material_change'),
    ('HEG-PRODUCER-1', 'emergency_release_protocol',  'no_exception_route')
) AS v (producer, name, value)
JOIN producer p ON p.name = v.producer
JOIN policy_attribute_definition ad ON ad.namespace = 'policy' AND ad.name = v.name
JOIN policy_attribute_scope sc ON sc.code = 'PRODUCER'
JOIN policy_attribute_definition_scope ads
    ON ads.attribute_definition_id = ad.id AND ads.attribute_scope_id = sc.id;
