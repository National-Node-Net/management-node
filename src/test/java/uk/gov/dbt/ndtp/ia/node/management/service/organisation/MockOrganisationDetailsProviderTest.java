/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.organisation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

class MockOrganisationDetailsProviderTest {

    @Test
    void mockOff_returnsNothing() throws IOException {
        assertThat(provider(false).findByKeys(List.of("ENV", "HEG", "BCC"))).isEmpty();
    }

    @Test
    void mockOn_returnsTheConfiguredDetails_matchingKeysCaseInsensitively() throws IOException {
        Map<String, OrganisationDetails> found = provider(true).findByKeys(List.of("env", "BCC", "UNKNOWN"));

        assertThat(found).containsOnlyKeys("env", "BCC");
        assertThat(found.get("env").description()).startsWith("The Environment Agency");
        assertThat(found.get("env").logoUrl()).isEqualTo("https://example.org/logos/env.svg");
        assertThat(found.get("BCC").description()).startsWith("Bristol City Council");
        assertThat(found.get("BCC").logoUrl()).isNull();
    }

    /** Binds the main application.yml (the test one shadows it on the classpath), so the mock data there is what is tested. */
    private static MockOrganisationDetailsProvider provider(boolean mock) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("test", Map.of("ORGANISATION_DETAILS_MOCK", mock)));
        new YamlPropertySourceLoader()
                .load("application", new FileSystemResource("src/main/resources/application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        MockOrganisationDetailsProvider.Properties properties = new Binder(
                        ConfigurationPropertySources.get(environment),
                        new PropertySourcesPlaceholdersResolver(environment))
                .bind("application.organisation-details", MockOrganisationDetailsProvider.Properties.class)
                .get();
        return new MockOrganisationDetailsProvider(properties);
    }
}
