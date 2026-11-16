/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- The rest are left without on purpose, to exercise the missing case.
UPDATE product p SET key_use_cases = v.key_use_cases
FROM (VALUES
    ('RiverLevelTelemetry', ARRAY[
        'Flood warning: Detect rising river levels early and alert communities at risk.',
        'Water resource planning: Track flows across catchments over time.',
        'Infrastructure design: Size bridges, culverts and defences against observed levels.']),
    ('AffordableHousingCompletions', ARRAY[
        'Housing delivery: Monitor progress against affordable housing targets.',
        'Investment: Identify areas where affordable supply is falling behind need.']),
    ('BristolAirQualitySensors', ARRAY[
        'Public health: Identify streets with persistently poor air quality.',
        'Transport planning: Assess the effect of traffic schemes on pollution.',
        'Policy: Evidence the case for clean air zones.'])
) AS v (name, key_use_cases)
WHERE p.name = v.name;
