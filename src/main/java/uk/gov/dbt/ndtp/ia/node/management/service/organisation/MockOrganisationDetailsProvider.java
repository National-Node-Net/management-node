/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.organisation;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Mock details from configuration, for local and dev only; returns nothing unless
 * {@code application.organisation-details.mock} is on.
 *
 * <p>TODO: replace with a client of the Organisation Service once it is available.
 */
@Component
@EnableConfigurationProperties(MockOrganisationDetailsProvider.Properties.class)
public class MockOrganisationDetailsProvider implements OrganisationDetailsProvider {

    private final Map<String, OrganisationDetails> organisations = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public MockOrganisationDetailsProvider(Properties properties) {
        if (properties.mock() && properties.organisations() != null) {
            organisations.putAll(properties.organisations());
        }
    }

    @Override
    public Map<String, OrganisationDetails> findByKeys(Collection<String> organisationKeys) {
        Map<String, OrganisationDetails> found = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String key : organisationKeys) {
            OrganisationDetails details = organisations.get(key);
            if (details != null) {
                found.put(key, details);
            }
        }
        return found;
    }

    @ConfigurationProperties(prefix = "application.organisation-details")
    public record Properties(boolean mock, Map<String, OrganisationDetails> organisations) {}
}
