/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.organisation.OrganisationDTO;

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
     * Finds an organisation by its unique key (e.g. {@code ORG_A}).
     *
     * @param organisationKey the organisation key
     * @return the organisation, or empty when no organisation carries that key
     */
    Optional<OrganisationDTO> findByKey(String organisationKey);

    /**
     * The row id of the organisation with {@code organisationKey}, for callers that need to key
     * a scoped lookup (such as {@code ORGANISATION} policy attributes) rather than read the
     * organisation itself. {@link OrganisationDTO} deliberately does not expose the id.
     *
     * @param organisationKey the organisation's stable key (e.g. {@code ORG_A})
     * @return the row id, or empty when the key is null, blank or matches no organisation
     */
    Optional<Long> findIdByKey(String organisationKey);

    /**
     * Finds several organisations at once, keyed by id - one query rather than one per id, for
     * callers assembling a response that mentions the same organisations repeatedly.
     *
     * @param ids the organisation ids to look up; null or empty yields an empty map
     * @return the organisations found, keyed by id. Ids with no matching row are absent.
     */
    Map<Long, OrganisationDTO> findByIds(Collection<Long> ids);
}
