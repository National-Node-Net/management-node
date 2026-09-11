/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.OrganisationDTO;

/**
 * Service interface for managing Organisation entities.
 */
public interface OrganisationService {

    /**
     * Finds an organisation by its database id.
     *
     * @param id the organisation id
     * @return the organisation, or empty when no organisation has that id
     */
    Optional<OrganisationDTO> findById(Long id);

    /**
     * Finds an organisation by its unique key (e.g. {@code ENV}).
     *
     * @param organisationKey the organisation key
     * @return the organisation, or empty when no organisation carries that key
     */
    Optional<OrganisationDTO> findByKey(String organisationKey);

    /**
     * Finds several organisations at once, keyed by id - one query rather than one per id, for
     * callers assembling a response that mentions the same organisations repeatedly.
     *
     * @param ids the organisation ids to look up; null or empty yields an empty map
     * @return the organisations found, keyed by id. Ids with no matching row are absent.
     */
    Map<Long, OrganisationDTO> findByIds(Collection<Long> ids);
}
