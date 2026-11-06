/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

-- BCC is left without a logo on purpose, to exercise the missing case.
UPDATE organisation o SET description = v.description, logo_url = v.logo_url
FROM (VALUES
    ('ENV',
     'The Environment Agency protects and improves the environment in England. It manages the risk '
     || 'of flooding from rivers and the sea, monitors water quality and river levels, and regulates '
     || 'major industry and waste.',
     'https://example.org/logos/env.svg'),
    ('HEG',
     'Homes England is the government''s housing and regeneration agency. It works with local '
     || 'authorities and developers to bring forward land for new homes and to support affordable '
     || 'housing across England.',
     'https://example.org/logos/heg.svg'),
    ('BCC',
     'Bristol City Council is the local authority for the city of Bristol, responsible for local '
     || 'planning, housing, transport and environmental services.',
     NULL)
) AS v(organisation_key, description, logo_url)
WHERE o.organisation_key = v.organisation_key;
