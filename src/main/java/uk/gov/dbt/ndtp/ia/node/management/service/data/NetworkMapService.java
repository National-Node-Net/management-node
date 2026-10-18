/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data;

import uk.gov.dbt.ndtp.ia.node.management.model.dto.network.NetworkMapDTO;

/** Reads the whole network - organisations, producers, consumers, products, subscriptions. */
public interface NetworkMapService {

    /**
     * The entire network as it stands in the database.
     *
     * Unfiltered: this is a topology view, and unlike product discovery it takes no criteria and
     * applies no policy. See {@link uk.gov.dbt.ndtp.ia.node.management.controller.v1.NetworkController}
     * for what that means and what still guards it.
     */
    NetworkMapDTO networkMap();
}
