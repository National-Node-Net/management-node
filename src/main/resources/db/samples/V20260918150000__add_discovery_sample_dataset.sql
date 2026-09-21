/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- A larger catalogue for exercising product discovery end to end. It adds no organisations: the
-- three existing ones (ENV, HEG, BCC) are the only identities testers have. See
-- docs/DISCOVERY_TEST_SCENARIOS.md for the expected outcome of each search over this data.
--
-- Lives in db/samples (local and dev only); never promote it to db/migration.

-- ---------------------------------------------------------------------------
-- More producers and consumers, so an organisation can have several of each
-- ---------------------------------------------------------------------------
INSERT INTO producer (name, description, org_id, active, host, port, tls, idp_client_id)
SELECT v.name, v.description, o.id, TRUE, v.host, 443, TRUE, v.idp_client_id
FROM (VALUES
    ('ENV-PRODUCER-2', 'ENV water and pollution producer', 'ENV', 'https://water.env.gov.uk', 'FEDERATOR_ENV'),
    ('HEG-PRODUCER-2', 'HEG housing programmes producer',  'HEG', 'https://programmes.heg.gov.uk', 'FEDERATOR_HEG'),
    ('BCC-PRODUCER-2', 'BCC city services producer',       'BCC', 'https://services.bcc.gov.uk', 'FEDERATOR_BCC')
) AS v (name, description, organisation_key, host, idp_client_id)
JOIN organisation o ON o.organisation_key = v.organisation_key;

INSERT INTO consumer (name, org_id, idp_client_id)
SELECT v.name, o.id, v.idp_client_id
FROM (VALUES
    ('ENV-CONSUMER-2', 'ENV', 'FEDERATOR_ENV'),
    ('HEG-CONSUMER-2', 'HEG', 'FEDERATOR_HEG'),
    ('BCC-CONSUMER-2', 'BCC', 'FEDERATOR_BCC')
) AS v (name, organisation_key, idp_client_id)
JOIN organisation o ON o.organisation_key = v.organisation_key;

-- ---------------------------------------------------------------------------
-- Products. type NULL = no product type; description NULL = none recorded.
-- 'Bristol_Cycle%Counts' carries LIKE metacharacters on purpose.
-- ---------------------------------------------------------------------------
INSERT INTO product (name, topic, producer_id, product_type_id, source, description)
SELECT v.name, 'topic.' || v.name, pr.id, pt.id, v.source, v.description
FROM (VALUES
    ('RiverLevelTelemetry',           'ENV-PRODUCER-1', 'topic', 'kafka://env/river-levels',
     'Hourly river level readings from gauging stations across England and Wales. Used for flood warning.'),
    ('CoastalErosionForecast',        'ENV-PRODUCER-1', 'file',  's3://env/coastal-erosion',
     'Experimental yearly forecast of coastal erosion for the English coastline.'),
    ('PollutionIncidentReports',      'ENV-PRODUCER-2', 'topic', 'kafka://env/pollution',
     'Reports of pollution incidents with the pseudonymised business responsible. Provisional until investigated.'),
    ('WaterAbstractionLicences',      'ENV-PRODUCER-2', 'file',  's3://env/abstraction',
     'Licences to abstract water, naming the licence holder. Directly identifiable.'),
    ('UnclassifiedDraftDataset',      'ENV-PRODUCER-2', 'file',  NULL,
     'A draft dataset whose identifiability and quality have not been recorded yet.'),
    ('AffordableHousingCompletions',  'HEG-PRODUCER-1', 'file',  's3://heg/completions',
     'Annual count of affordable housing completions per property type. Anonymised.'),
    ('HelpToBuyApplicants',           'HEG-PRODUCER-2', 'topic', 'kafka://heg/help-to-buy',
     'Individual applicants to the Help to Buy scheme, including households with children.'),
    ('HousingNeedSurvey',             'HEG-PRODUCER-2', 'file',  's3://heg/need-survey',
     'Experimental household survey of housing need across England and Scotland.'),
    ('EmptyHomesRegister',            'HEG-PRODUCER-1', 'file',  's3://heg/empty-homes',
     'Register of long-term empty homes. Superseded by the council tax base return.'),
    ('ScotlandLandRegisterExtract',   'HEG-PRODUCER-1', 'file',  's3://heg/scotland-land',
     'Extract of land ownership parcels covering Scotland only.'),
    ('BristolAirQualitySensors',      'BCC-PRODUCER-1', 'topic', 'kafka://bcc/air-quality',
     'Hourly air quality readings from sensors across Bristol. No personal data.'),
    ('BristolSchoolPlaces',           'BCC-PRODUCER-2', 'file',  's3://bcc/school-places',
     'Anonymised school place allocations for Bristol children, published each year.'),
    ('BristolTemporaryAccommodation', 'BCC-PRODUCER-2', 'topic', 'kafka://bcc/temp-accommodation',
     'Households placed in temporary accommodation in Bristol, including survivors of domestic abuse.'),
    ('Bristol_Cycle%Counts',          'BCC-PRODUCER-1', NULL,    NULL,
     NULL)
) AS v (name, producer, type, source, description)
JOIN producer pr ON pr.name = v.producer
LEFT JOIN product_type pt ON pt.name = v.type;

-- ---------------------------------------------------------------------------
-- A new PRODUCT attribute: where the data is about. Policy matches it against the calling
-- organisation's jurisdictions, so organisation attributes drive the discovery query.
-- ---------------------------------------------------------------------------
INSERT INTO policy_attribute_definition
    (namespace, name, display_name, description, data_type, multi_valued, allowed_values, sensitive, created_by)
VALUES
    ('policy', 'coverage_jurisdictions', 'Coverage jurisdictions',
     'The nations or local areas the product''s data covers. Compared with the jurisdictions of the organisation asking.',
     'STRING', TRUE, NULL, FALSE, 'sample-data');

INSERT INTO policy_attribute_definition_scope
    (attribute_definition_id, attribute_scope_id, required, created_by)
SELECT ad.id, sc.id, FALSE, 'sample-data'
FROM policy_attribute_definition ad
JOIN policy_attribute_scope sc ON sc.code = 'PRODUCT'
WHERE ad.namespace = 'policy' AND ad.name = 'coverage_jurisdictions';

-- ---------------------------------------------------------------------------
-- PRODUCT attribute values (one row per value; multi-valued attributes repeat the product)
-- ---------------------------------------------------------------------------
INSERT INTO policy_attribute_value
    (attribute_definition_scope_id, entity_id, value, created_by)
SELECT ads.id, pr.id, to_jsonb(v.value::text), 'sample-data'
FROM (VALUES
    -- the three original products only gain a coverage
    ('FloodRiskMapZones',             'coverage_jurisdictions', 'England'),
    ('FloodRiskMapZones',             'coverage_jurisdictions', 'Wales'),
    ('BrownfieldLandAvailability',    'coverage_jurisdictions', 'England'),
    ('PendingPlanningApplications',   'coverage_jurisdictions', 'Bristol'),

    ('RiverLevelTelemetry',           'record_unit',            'geographic_area'),
    ('RiverLevelTelemetry',           'identifiability',        'non_personal'),
    ('RiverLevelTelemetry',           'temporal_resolution',    'hourly'),
    ('RiverLevelTelemetry',           'quality_designation',    'validated'),
    ('RiverLevelTelemetry',           'coverage_jurisdictions', 'England'),
    ('RiverLevelTelemetry',           'coverage_jurisdictions', 'Wales'),

    ('CoastalErosionForecast',        'record_unit',            'geographic_area'),
    ('CoastalErosionForecast',        'identifiability',        'non_personal'),
    ('CoastalErosionForecast',        'temporal_resolution',    'annual'),
    ('CoastalErosionForecast',        'quality_designation',    'experimental'),
    ('CoastalErosionForecast',        'coverage_jurisdictions', 'England'),

    ('PollutionIncidentReports',      'record_unit',            'business'),
    ('PollutionIncidentReports',      'identifiability',        'pseudonymised'),
    ('PollutionIncidentReports',      'temporal_resolution',    'event_level'),
    ('PollutionIncidentReports',      'quality_designation',    'provisional'),
    ('PollutionIncidentReports',      'coverage_jurisdictions', 'England'),
    ('PollutionIncidentReports',      'coverage_jurisdictions', 'Wales'),

    ('WaterAbstractionLicences',      'record_unit',            'business'),
    ('WaterAbstractionLicences',      'identifiability',        'directly_identifiable'),
    ('WaterAbstractionLicences',      'temporal_resolution',    'monthly'),
    ('WaterAbstractionLicences',      'quality_designation',    'validated'),
    ('WaterAbstractionLicences',      'coverage_jurisdictions', 'England'),

    -- deliberately missing identifiability and quality_designation
    ('UnclassifiedDraftDataset',      'record_unit',            'geographic_area'),
    ('UnclassifiedDraftDataset',      'coverage_jurisdictions', 'England'),

    ('AffordableHousingCompletions',  'record_unit',            'property'),
    ('AffordableHousingCompletions',  'identifiability',        'anonymised'),
    ('AffordableHousingCompletions',  'temporal_resolution',    'annual'),
    ('AffordableHousingCompletions',  'quality_designation',    'validated'),
    ('AffordableHousingCompletions',  'coverage_jurisdictions', 'England'),

    ('HelpToBuyApplicants',           'record_unit',            'person'),
    ('HelpToBuyApplicants',           'identifiability',        'directly_identifiable'),
    ('HelpToBuyApplicants',           'population_risk_tags',   'children'),
    ('HelpToBuyApplicants',           'temporal_resolution',    'event_level'),
    ('HelpToBuyApplicants',           'quality_designation',    'validated'),
    ('HelpToBuyApplicants',           'coverage_jurisdictions', 'England'),

    ('HousingNeedSurvey',             'record_unit',            'household'),
    ('HousingNeedSurvey',             'identifiability',        'pseudonymised'),
    ('HousingNeedSurvey',             'temporal_resolution',    'annual'),
    ('HousingNeedSurvey',             'quality_designation',    'experimental'),
    ('HousingNeedSurvey',             'coverage_jurisdictions', 'England'),
    ('HousingNeedSurvey',             'coverage_jurisdictions', 'Scotland'),

    ('EmptyHomesRegister',            'record_unit',            'property'),
    ('EmptyHomesRegister',            'identifiability',        'pseudonymised'),
    ('EmptyHomesRegister',            'temporal_resolution',    'monthly'),
    ('EmptyHomesRegister',            'quality_designation',    'superseded'),
    ('EmptyHomesRegister',            'coverage_jurisdictions', 'England'),

    ('ScotlandLandRegisterExtract',   'record_unit',            'property'),
    ('ScotlandLandRegisterExtract',   'identifiability',        'anonymised'),
    ('ScotlandLandRegisterExtract',   'temporal_resolution',    'annual'),
    ('ScotlandLandRegisterExtract',   'quality_designation',    'validated'),
    ('ScotlandLandRegisterExtract',   'coverage_jurisdictions', 'Scotland'),

    ('BristolAirQualitySensors',      'record_unit',            'geographic_area'),
    ('BristolAirQualitySensors',      'identifiability',        'non_personal'),
    ('BristolAirQualitySensors',      'temporal_resolution',    'hourly'),
    ('BristolAirQualitySensors',      'quality_designation',    'validated'),
    ('BristolAirQualitySensors',      'coverage_jurisdictions', 'Bristol'),

    ('BristolSchoolPlaces',           'record_unit',            'person'),
    ('BristolSchoolPlaces',           'identifiability',        'anonymised'),
    ('BristolSchoolPlaces',           'population_risk_tags',   'children'),
    ('BristolSchoolPlaces',           'temporal_resolution',    'annual'),
    ('BristolSchoolPlaces',           'quality_designation',    'provisional'),
    ('BristolSchoolPlaces',           'coverage_jurisdictions', 'Bristol'),

    ('BristolTemporaryAccommodation', 'record_unit',            'household'),
    ('BristolTemporaryAccommodation', 'identifiability',        'directly_identifiable'),
    ('BristolTemporaryAccommodation', 'population_risk_tags',   'domestic_abuse_survivors'),
    ('BristolTemporaryAccommodation', 'temporal_resolution',    'event_level'),
    ('BristolTemporaryAccommodation', 'quality_designation',    'validated'),
    ('BristolTemporaryAccommodation', 'coverage_jurisdictions', 'Bristol'),

    ('Bristol_Cycle%Counts',          'record_unit',            'geographic_area'),
    ('Bristol_Cycle%Counts',          'identifiability',        'non_personal'),
    ('Bristol_Cycle%Counts',          'temporal_resolution',    'daily'),
    ('Bristol_Cycle%Counts',          'quality_designation',    'validated'),
    ('Bristol_Cycle%Counts',          'coverage_jurisdictions', 'Bristol')
) AS v (product, name, value)
JOIN product pr ON pr.name = v.product
JOIN policy_attribute_definition ad ON ad.namespace = 'policy' AND ad.name = v.name
JOIN policy_attribute_scope sc ON sc.code = 'PRODUCT'
JOIN policy_attribute_definition_scope ads
    ON ads.attribute_definition_id = ad.id AND ads.attribute_scope_id = sc.id;

-- ---------------------------------------------------------------------------
-- Grants (subscriptions). A product_consumer row IS access: products below range from no
-- accessing organisation, through one, to several consumers of the same organisation.
-- validity 0 follows the non-expiring convention of the other sample grants.
-- ---------------------------------------------------------------------------
INSERT INTO product_consumer (product_id, consumer_id, granted_ts, validity, schedule_type, schedule_expression)
SELECT pr.id, c.id, v.granted_ts::timestamp, 0, v.schedule_type, v.schedule_expression
FROM (VALUES
    ('RiverLevelTelemetry',          'BCC-CONSUMER-1', '2026-01-10 09:00:00', 'interval', 'PT1H'),
    ('RiverLevelTelemetry',          'BCC-CONSUMER-2', '2026-02-01 09:00:00', 'interval', 'PT6H'),
    ('RiverLevelTelemetry',          'HEG-CONSUMER-1', '2026-03-15 09:00:00', 'cron',     '0 0 6 * * *'),
    ('PollutionIncidentReports',     'BCC-CONSUMER-1', '2026-04-02 10:30:00', 'cron',     '0 0 * * * *'),
    ('AffordableHousingCompletions', 'BCC-CONSUMER-2', '2026-01-20 08:00:00', 'cron',     '0 0 1 1 * *'),
    ('AffordableHousingCompletions', 'ENV-CONSUMER-1', '2026-05-05 08:00:00', 'cron',     '0 0 1 1 * *'),
    ('HelpToBuyApplicants',          'ENV-CONSUMER-2', '2026-06-01 12:00:00', 'interval', 'P1D'),
    ('BristolAirQualitySensors',     'ENV-CONSUMER-1', '2026-02-11 07:00:00', 'interval', 'PT1H'),
    ('BristolAirQualitySensors',     'ENV-CONSUMER-2', '2026-02-12 07:00:00', 'interval', 'PT1H'),
    ('BristolAirQualitySensors',     'HEG-CONSUMER-2', '2026-07-01 07:00:00', 'cron',     '0 0 7 * * *'),
    ('BristolTemporaryAccommodation','HEG-CONSUMER-1', '2026-03-03 15:00:00', 'cron',     '0 0 2 * * *')
) AS v (product, consumer, granted_ts, schedule_type, schedule_expression)
JOIN product pr ON pr.name = v.product
JOIN consumer c ON c.name = v.consumer;
