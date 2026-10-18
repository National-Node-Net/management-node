/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterScope;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository.FacetCount;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository.FilterAttributeDefinition;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.DiscoverySchema;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.FacetQuery;

/**
 * Covers the two reads the filter panel is built from: the live {@code PRODUCT} and {@code
 * ORGANISATION} attribute definitions, and running one caller-supplied facet-count statement.
 * Follows {@code NetworkMapRepositoryTest}'s pattern of capturing the SQL string and the {@link
 * RowMapper} with the JDBC template mocked.
 */
@ExtendWith(MockitoExtension.class)
class ProductDiscoveryRepositoryFilterOptionsTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Mock
    private ResultSet row;

    @Captor
    private ArgumentCaptor<String> sql;

    @Captor
    private ArgumentCaptor<RowMapper<FilterAttributeDefinition>> definitionMapper;

    @Captor
    private ArgumentCaptor<RowMapper<FacetCount>> facetMapper;

    private ProductDiscoveryRepository repository;

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        repository = new ProductDiscoveryRepository(jdbc, new DiscoverySchema("mn"));

        logger = (Logger) LoggerFactory.getLogger(ProductDiscoveryRepository.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void findFilterAttributeDefinitions_readsLiveProductAndOrganisationDefinitions() throws SQLException {
        when(jdbc.query(anyString(), anyMap(), definitionMapper.capture())).thenReturn(List.of());

        repository.findFilterAttributeDefinitions();

        verify(jdbc).query(sql.capture(), anyMap(), definitionMapper.capture());
        assertThat(sql.getValue())
                .contains(
                        "\"mn\".policy_attribute_definition d",
                        "JOIN \"mn\".policy_attribute_definition_scope ds ON ds.attribute_definition_id = d.id",
                        "JOIN \"mn\".policy_attribute_scope s ON s.id = ds.attribute_scope_id",
                        "s.code IN ('PRODUCT', 'ORGANISATION')",
                        "d.is_deleted = FALSE",
                        "ds.is_deleted = FALSE",
                        "ORDER BY s.code DESC, d.name, d.namespace");

        when(row.getString("scope_code")).thenReturn("ORGANISATION");
        when(row.getString("name")).thenReturn("jurisdictions");
        when(row.getString("display_name")).thenReturn(null);
        when(row.getString("data_type")).thenReturn("STRING");
        when(row.getBoolean("multi_valued")).thenReturn(true);
        when(row.getBoolean("sensitive")).thenReturn(false);

        FilterAttributeDefinition mapped = definitionMapper.getValue().mapRow(row, 0);

        assertThat(mapped)
                .isEqualTo(new FilterAttributeDefinition(
                        FilterScope.ORGANISATION, "jurisdictions", null, "STRING", true, false));
    }

    @Test
    void findFilterAttributeDefinitions_keepsTheFirstDefinitionOfADuplicatedName() {
        FilterAttributeDefinition first =
                new FilterAttributeDefinition(FilterScope.PRODUCT, "record_unit", "First", "STRING", false, false);
        FilterAttributeDefinition duplicate =
                new FilterAttributeDefinition(FilterScope.PRODUCT, "record_unit", "Second", "STRING", false, false);
        when(jdbc.query(anyString(), anyMap(), definitionMapper.capture())).thenReturn(List.of(first, duplicate));

        List<FilterAttributeDefinition> definitions = repository.findFilterAttributeDefinitions();

        assertThat(definitions).containsExactly(first);
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("record_unit").contains("PRODUCT");
        });
    }

    @Test
    void findFacetCounts_runsTheGivenStatementAndMapsNullValuesThrough() throws SQLException {
        FacetQuery query = new FacetQuery("SELECT ...", Map.of("p0", "x"));
        when(jdbc.query(eq("SELECT ..."), eq(Map.of("p0", "x")), facetMapper.capture()))
                .thenReturn(List.of());

        repository.findFacetCounts(query);

        verify(jdbc).query(eq("SELECT ..."), eq(Map.of("p0", "x")), facetMapper.capture());

        when(row.getString("facet")).thenReturn("field:format");
        when(row.getString("value")).thenReturn(null);
        when(row.getLong("matching")).thenReturn(3L);

        FacetCount mapped = facetMapper.getValue().mapRow(row, 0);

        assertThat(mapped).isEqualTo(new FacetCount("field:format", null, 3L));
    }
}
