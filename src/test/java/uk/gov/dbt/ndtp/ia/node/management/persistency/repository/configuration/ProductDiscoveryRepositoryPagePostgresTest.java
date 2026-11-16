/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.AbstractPostgresRepositoryTest;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.DiscoverySchema;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchQuery;

class ProductDiscoveryRepositoryPagePostgresTest extends AbstractPostgresRepositoryTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void findPage_readsATextArrayAsAListInOrder() {
        ProductDiscoveryRepository repository = new ProductDiscoveryRepository(jdbc, new DiscoverySchema(""));

        List<Map<String, Object>> rows = repository.findPage(new ProductSearchQuery(
                "SELECT ARRAY['b', 'a']::text[] AS key_use_cases, CAST(NULL AS text[]) AS none, 'x' AS name",
                "",
                Map.of()));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("key_use_cases")).isEqualTo(List.of("b", "a"));
            assertThat(row.get("none")).isNull();
            assertThat(row.get("name")).isEqualTo("x");
        });
    }
}
