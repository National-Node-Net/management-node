/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- A long description for some of the sample products, so the details page has something to show
-- and free text can be seen matching a word that only the long description contains: 'hydrometric'
-- appears nowhere else, nor does 'methodology' (in two of them). The rest are left without on
-- purpose, so the missing case can be exercised.
UPDATE product p SET long_description = v.long_description
FROM (VALUES
    ('RiverLevelTelemetry',
     'Readings are taken every fifteen minutes at each gauging station and published as hourly means. '
     || 'They are quality checked against the national hydrometric archive before the end of each day, '
     || 'and a reading that fails the check is flagged rather than removed.'),
    ('CoastalErosionForecast',
     'Methodology: the forecast combines historical shoreline surveys with projected sea level rise '
     || 'and storm frequency. It is experimental, published once a year, and not suitable for '
     || 'individual property decisions.'),
    ('AffordableHousingCompletions',
     'Counts are reported by local authorities each quarter and summed per financial year. '
     || 'Completions are broken down by tenure (social rent, affordable rent, shared ownership) and '
     || 'property type. Figures for the latest year are provisional until the annual return closes.'),
    ('HousingNeedSurvey',
     'Methodology: a stratified sample of households is interviewed each year about their current '
     || 'housing, its condition and affordability, and whether anyone in the household needs a home '
     || 'of their own.'),
    ('BristolAirQualitySensors',
     'Sensors measure nitrogen dioxide, PM2.5 and PM10 at roadside and background sites across the '
     || 'city. Each sensor reports every ten minutes; the published readings are hourly averages.')
) AS v (name, long_description)
WHERE p.name = v.name;
