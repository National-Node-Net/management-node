/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- A display name and a format for each sample product. Two are left without on purpose, so the
-- missing cases can be exercised: 'UnclassifiedDraftDataset' has neither, and
-- 'Bristol_Cycle%Counts' (which already has no type, source or description) has neither either.
UPDATE product p SET display_name = v.display_name, format = v.format
FROM (VALUES
    ('FloodRiskMapZones',             'Flood Risk Map Zones',               'GeoJSON'),
    ('BrownfieldLandAvailability',    'Brownfield Land Availability',       'CSV'),
    ('PendingPlanningApplications',   'Pending Planning Applications',      'JSON'),
    ('RiverLevelTelemetry',           'River Level Telemetry',              'JSON'),
    ('CoastalErosionForecast',        'Coastal Erosion Forecast',           'NetCDF'),
    ('PollutionIncidentReports',      'Pollution Incident Reports',         'XML'),
    ('WaterAbstractionLicences',      'Water Abstraction Licences',         'CSV'),
    ('AffordableHousingCompletions',  'Affordable Housing Completions',     'CSV'),
    ('HelpToBuyApplicants',           'Help to Buy Applicants',             'Avro'),
    ('HousingNeedSurvey',             'Housing Need Survey',                'Parquet'),
    ('EmptyHomesRegister',            'Empty Homes Register',               'XML'),
    ('ScotlandLandRegisterExtract',   'Scotland Land Register Extract',     'RDF'),
    ('BristolAirQualitySensors',      'Bristol Air Quality Sensors',        'JSON'),
    ('BristolSchoolPlaces',           'Bristol School Places',              'CSV'),
    ('BristolTemporaryAccommodation', 'Bristol Temporary Accommodation',    'JSON')
) AS v (name, display_name, format)
WHERE p.name = v.name;
