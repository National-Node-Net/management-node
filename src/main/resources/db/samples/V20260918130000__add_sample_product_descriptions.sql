/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

UPDATE product SET description =
    'Flood risk zones for England and Wales as geographic areas, refreshed daily from Environment Agency models. Contains no personal data.'
WHERE name = 'FloodRiskMapZones';

UPDATE product SET description =
    'Brownfield land parcels available for housing development, one record per property, published annually. Owner references are pseudonymised.'
WHERE name = 'BrownfieldLandAvailability';

UPDATE product SET description =
    'Planning applications awaiting a decision, at household level and event resolution. Directly identifiable, and includes protected addresses.'
WHERE name = 'PendingPlanningApplications';
