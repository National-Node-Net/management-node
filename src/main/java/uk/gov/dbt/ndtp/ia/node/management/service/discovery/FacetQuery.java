/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import java.util.Map;

/**
 * One facet-count statement, already assembled with the contract's row filter and bound to its
 * parameters, ready for {@code ProductDiscoveryRepository.findFacetCounts} to run. At most one of
 * these is run per filter-panel request, whatever the number of filter groups it counts.
 *
 * @param sql the statement; its result rows are read as {@code facet}, {@code value} and {@code
 *     matching}
 * @param parameters the bound values of the statement
 */
public record FacetQuery(String sql, Map<String, Object> parameters) {}
