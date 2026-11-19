/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.organisation;

import java.util.Collection;
import java.util.Map;

/**
 * The description and logo of organisations. The Organisation Service, once built, is their source
 * of truth; until then {@link MockOrganisationDetailsProvider} stands in.
 */
public interface OrganisationDetailsProvider {

    /** The details of those of {@code organisationKeys} that have any, by key (case-insensitive). */
    Map<String, OrganisationDetails> findByKeys(Collection<String> organisationKeys);
}
