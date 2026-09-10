/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- Sample CONSUMER-scoped policy attributes for the three sample consumers, describing who is asking and
-- how they will process what they get - the counterpart to the ORGANISATION attributes, which describe
-- what the requesting body is entitled to hold.
--
-- Same storage shape as the ORGANISATION samples: one row per value, each a JSON scalar. Only
-- assurance_evidence is multi-valued; the other four are single-select vocabularies, so each has its
-- full option list in allowed_values even where no sample consumer uses every option.

INSERT INTO policy_attribute_definition
    (namespace, name, display_name, description, data_type, multi_valued, allowed_values, sensitive, created_by)
VALUES
    ('policy', 'public_function', 'Public function',
     'The public function the consumer performs, used to decide whether a product is relevant to its statutory role.',
     'STRING', FALSE,
     '["benefit_administration", "environmental_enforcement", "emergency_response", "national_statistics", "infrastructure_planning"]'::jsonb,
     FALSE, 'sample-data'),
    ('policy', 'operating_remit', 'Operating remit',
     'The geographic level the consumer operates at. Local remits generally need a boundary filter applied to results.',
     'STRING', FALSE,
     '["national", "devolved_nation", "regional", "local_authority", "cross_border"]'::jsonb,
     FALSE, 'sample-data'),
    ('policy', 'processing_environment', 'Processing environment',
     'Where the consumer will process the data, used to gate detailed records on a sufficiently controlled environment.',
     'STRING', FALSE,
     '["trusted_research_environment", "department_managed_cloud", "on_premises_secure_zone", "contractor_hosted_platform"]'::jsonb,
     FALSE, 'sample-data'),
    ('policy', 'assurance_evidence', 'Assurance evidence',
     'Current assurance the consumer holds. Multi-valued: a policy can require a specific combination to be present.',
     'STRING', TRUE,
     '["independent_security_audit", "research_accreditation", "staff_vetting", "incident_response_exercise"]'::jsonb,
     FALSE, 'sample-data'),
    ('policy', 'decision_automation_level', 'Decision automation level',
     'How far decisions made from the data are automated, so a product excluding automated decision-making can deny it.',
     'STRING', FALSE,
     '["analysis_only", "human_decision_support", "automated_with_human_review", "fully_automated"]'::jsonb,
     FALSE, 'sample-data');

-- Bind to the CONSUMER scope. public_function and operating_remit are required: a consumer with neither
-- cannot be matched against any product remit at all, which is a data problem rather than a deny decision.
INSERT INTO policy_attribute_definition_scope
    (attribute_definition_id, attribute_scope_id, required, created_by)
SELECT ad.id, sc.id, v.required, 'sample-data'
FROM (VALUES
    ('public_function', TRUE),
    ('operating_remit', TRUE),
    ('processing_environment', FALSE),
    ('assurance_evidence', FALSE),
    ('decision_automation_level', FALSE)
) AS v (name, required)
JOIN policy_attribute_definition ad ON ad.namespace = 'policy' AND ad.name = v.name
JOIN policy_attribute_scope sc ON sc.code = 'CONSUMER';

-- Values per consumer. Spread so each of the five attributes decides something on its own locally:
-- HEG is the only one in a trusted research environment, BCC the only local_authority remit, and ENV is
-- deliberately fully_automated so a product that excludes automated decision-making has something to deny.
INSERT INTO policy_attribute_value
    (attribute_definition_scope_id, entity_id, value, created_by)
SELECT ads.id, c.id, to_jsonb(v.value::text), 'sample-data'
FROM (VALUES
    -- Environment Agency consumer
    ('ENV-CONSUMER-1', 'public_function',           'environmental_enforcement'),
    ('ENV-CONSUMER-1', 'operating_remit',           'national'),
    ('ENV-CONSUMER-1', 'processing_environment',    'department_managed_cloud'),
    ('ENV-CONSUMER-1', 'assurance_evidence',        'independent_security_audit'),
    ('ENV-CONSUMER-1', 'assurance_evidence',        'staff_vetting'),
    ('ENV-CONSUMER-1', 'assurance_evidence',        'incident_response_exercise'),
    ('ENV-CONSUMER-1', 'decision_automation_level', 'fully_automated'),

    -- Bristol City Council consumer
    ('BCC-CONSUMER-1', 'public_function',           'emergency_response'),
    ('BCC-CONSUMER-1', 'operating_remit',           'local_authority'),
    ('BCC-CONSUMER-1', 'processing_environment',    'on_premises_secure_zone'),
    ('BCC-CONSUMER-1', 'assurance_evidence',        'staff_vetting'),
    ('BCC-CONSUMER-1', 'decision_automation_level', 'human_decision_support'),

    -- Homes England consumer
    ('HEG-CONSUMER-1', 'public_function',           'infrastructure_planning'),
    ('HEG-CONSUMER-1', 'operating_remit',           'national'),
    ('HEG-CONSUMER-1', 'processing_environment',    'trusted_research_environment'),
    ('HEG-CONSUMER-1', 'assurance_evidence',        'independent_security_audit'),
    ('HEG-CONSUMER-1', 'assurance_evidence',        'research_accreditation'),
    ('HEG-CONSUMER-1', 'decision_automation_level', 'analysis_only')
) AS v (consumer, name, value)
JOIN consumer c ON c.name = v.consumer
JOIN policy_attribute_definition ad ON ad.namespace = 'policy' AND ad.name = v.name
JOIN policy_attribute_scope sc ON sc.code = 'CONSUMER'
JOIN policy_attribute_definition_scope ads
    ON ads.attribute_definition_id = ad.id AND ads.attribute_scope_id = sc.id;
