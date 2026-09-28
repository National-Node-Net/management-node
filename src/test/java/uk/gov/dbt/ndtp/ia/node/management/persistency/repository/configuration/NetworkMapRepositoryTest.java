/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.network.NetworkMapDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.DiscoverySchema;

/**
 * Covers the five reads the network map is built from, with the JDBC template and result set
 * mocked: the SQL each read sends (its schema prefix, joins, filter and ordering) and how its
 * row mapper turns a row into a DTO, including the nullable reads that keep "unknown" distinct
 * from "false".
 */
@ExtendWith(MockitoExtension.class)
class NetworkMapRepositoryTest {

    private static final LocalDateTime GRANTED = LocalDateTime.of(2026, 9, 1, 10, 30);

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Mock
    private ResultSet row;

    @Captor
    private ArgumentCaptor<String> sql;

    @Captor
    private ArgumentCaptor<RowMapper<NetworkMapDTO.Organisation>> organisationMapper;

    @Captor
    private ArgumentCaptor<RowMapper<NetworkMapDTO.Producer>> producerMapper;

    @Captor
    private ArgumentCaptor<RowMapper<NetworkMapDTO.Consumer>> consumerMapper;

    @Captor
    private ArgumentCaptor<RowMapper<NetworkMapDTO.Product>> productMapper;

    @Captor
    private ArgumentCaptor<RowMapper<NetworkMapDTO.Subscription>> subscriptionMapper;

    private NetworkMapRepository repository;

    @BeforeEach
    void setUp() {
        repository = new NetworkMapRepository(jdbc, new DiscoverySchema("mn"));
    }

    @Test
    void findOrganisations_returnsWhatTheQueryReturns() {
        NetworkMapDTO.Organisation organisation =
                NetworkMapDTO.Organisation.builder().id(1L).key("ENV").build();
        when(jdbc.query(anyString(), organisationMapper.capture())).thenReturn(List.of(organisation));

        assertThat(repository.findOrganisations()).containsExactly(organisation);
    }

    @Test
    void findOrganisations_selectsOnlyOrganisationsWithAProducerOrConsumer_orderedByKey() {
        repository.findOrganisations();

        verify(jdbc).query(sql.capture(), organisationMapper.capture());
        assertThat(sql.getValue())
                .contains("FROM \"mn\".organisation o")
                .contains("EXISTS (SELECT 1 FROM \"mn\".producer p WHERE p.org_id = o.id)")
                .contains("OR EXISTS (SELECT 1 FROM \"mn\".consumer c WHERE c.org_id = o.id)")
                .endsWith("ORDER BY o.organisation_key");
    }

    @Test
    void organisationMapper_mapsEveryColumn() throws SQLException {
        repository.findOrganisations();
        verify(jdbc).query(anyString(), organisationMapper.capture());
        when(row.getLong("id")).thenReturn(7L);
        when(row.getString("organisation_key")).thenReturn("ENV");
        when(row.getString("name")).thenReturn("Environment Agency");

        NetworkMapDTO.Organisation organisation = organisationMapper.getValue().mapRow(row, 0);

        assertThat(organisation.id()).isEqualTo(7L);
        assertThat(organisation.key()).isEqualTo("ENV");
        assertThat(organisation.name()).isEqualTo("Environment Agency");
    }

    @Test
    void findProducers_joinsTheOrganisation_orderedByOrganisationThenName() {
        repository.findProducers();

        verify(jdbc).query(sql.capture(), producerMapper.capture());
        assertThat(sql.getValue())
                .contains("FROM \"mn\".producer pr")
                .contains("JOIN \"mn\".organisation o ON o.id = pr.org_id")
                .endsWith("ORDER BY o.organisation_key, pr.name");
    }

    @Test
    void producerMapper_mapsEveryColumn() throws SQLException {
        repository.findProducers();
        verify(jdbc).query(anyString(), producerMapper.capture());
        when(row.getLong("id")).thenReturn(3L);
        when(row.getString("name")).thenReturn("ENV producer");
        when(row.getString("description")).thenReturn("Publishes flood data");
        when(row.getBoolean("active")).thenReturn(true);
        when(row.wasNull()).thenReturn(false);
        when(row.getString("organisation_key")).thenReturn("ENV");

        NetworkMapDTO.Producer producer = producerMapper.getValue().mapRow(row, 0);

        assertThat(producer.id()).isEqualTo(3L);
        assertThat(producer.name()).isEqualTo("ENV producer");
        assertThat(producer.description()).isEqualTo("Publishes flood data");
        assertThat(producer.active()).isTrue();
        assertThat(producer.organisationKey()).isEqualTo("ENV");
    }

    @Test
    void producerMapper_readsFalseActiveAsInactive() throws SQLException {
        repository.findProducers();
        verify(jdbc).query(anyString(), producerMapper.capture());
        when(row.getBoolean("active")).thenReturn(false);
        when(row.wasNull()).thenReturn(false);

        assertThat(producerMapper.getValue().mapRow(row, 0).active()).isFalse();
    }

    @Test
    void producerMapper_readsNullActiveAsUnknownRatherThanInactive() throws SQLException {
        repository.findProducers();
        verify(jdbc).query(anyString(), producerMapper.capture());
        when(row.getBoolean("active")).thenReturn(false);
        when(row.wasNull()).thenReturn(true);

        assertThat(producerMapper.getValue().mapRow(row, 0).active()).isNull();
    }

    @Test
    void findConsumers_joinsTheOrganisation_orderedByOrganisationThenName() {
        repository.findConsumers();

        verify(jdbc).query(sql.capture(), consumerMapper.capture());
        assertThat(sql.getValue())
                .contains("c.is_default, c.schedule_type, c.schedule_expression")
                .contains("FROM \"mn\".consumer c")
                .contains("JOIN \"mn\".organisation o ON o.id = c.org_id")
                .endsWith("ORDER BY o.organisation_key, c.name");
    }

    @Test
    void consumerMapper_mapsEveryColumn() throws SQLException {
        repository.findConsumers();
        verify(jdbc).query(anyString(), consumerMapper.capture());
        when(row.getLong("id")).thenReturn(5L);
        when(row.getString("name")).thenReturn("ENV consumer");
        when(row.getString("organisation_key")).thenReturn("ENV");
        when(row.getBoolean("is_default")).thenReturn(true);
        when(row.wasNull()).thenReturn(false);
        when(row.getString("schedule_type")).thenReturn("cron");
        when(row.getString("schedule_expression")).thenReturn("0 0 * * *");

        NetworkMapDTO.Consumer consumer = consumerMapper.getValue().mapRow(row, 0);

        assertThat(consumer.id()).isEqualTo(5L);
        assertThat(consumer.name()).isEqualTo("ENV consumer");
        assertThat(consumer.organisationKey()).isEqualTo("ENV");
        assertThat(consumer.defaultConsumer()).isTrue();
        assertThat(consumer.scheduleType()).isEqualTo("cron");
        assertThat(consumer.scheduleExpression()).isEqualTo("0 0 * * *");
    }

    @Test
    void consumerMapper_readsNullIsDefaultAsUnknown() throws SQLException {
        repository.findConsumers();
        verify(jdbc).query(anyString(), consumerMapper.capture());
        when(row.getBoolean("is_default")).thenReturn(false);
        when(row.wasNull()).thenReturn(true);

        assertThat(consumerMapper.getValue().mapRow(row, 0).defaultConsumer()).isNull();
    }

    @Test
    void findProducts_leftJoinsTheProductType_soAnUntypedProductIsKept() {
        repository.findProducts();

        verify(jdbc).query(sql.capture(), productMapper.capture());
        assertThat(sql.getValue())
                .contains("FROM \"mn\".product p")
                .contains("JOIN \"mn\".producer pr ON pr.id = p.producer_id")
                .contains("JOIN \"mn\".organisation o ON o.id = pr.org_id")
                .contains("LEFT JOIN \"mn\".product_type pt ON pt.id = p.product_type_id")
                .endsWith("ORDER BY p.name");
    }

    @Test
    void productMapper_mapsEveryColumn() throws SQLException {
        repository.findProducts();
        verify(jdbc).query(anyString(), productMapper.capture());
        when(row.getLong("id")).thenReturn(11L);
        when(row.getString("name")).thenReturn("Flood alerts");
        when(row.getString("description")).thenReturn("Live flood warnings");
        when(row.getString("topic")).thenReturn("env.flood.alerts");
        when(row.getString("type")).thenReturn("topic");
        when(row.getString("source")).thenReturn("kafka://env");
        when(row.getLong("producer_id")).thenReturn(3L);
        when(row.getString("organisation_key")).thenReturn("ENV");

        NetworkMapDTO.Product product = productMapper.getValue().mapRow(row, 0);

        assertThat(product.id()).isEqualTo(11L);
        assertThat(product.name()).isEqualTo("Flood alerts");
        assertThat(product.description()).isEqualTo("Live flood warnings");
        assertThat(product.topic()).isEqualTo("env.flood.alerts");
        assertThat(product.type()).isEqualTo("topic");
        assertThat(product.source()).isEqualTo("kafka://env");
        assertThat(product.producerId()).isEqualTo(3L);
        assertThat(product.organisationKey()).isEqualTo("ENV");
    }

    @Test
    void findSubscriptions_readsEveryGrant_orderedByProductThenConsumer() {
        repository.findSubscriptions();

        verify(jdbc).query(sql.capture(), subscriptionMapper.capture());
        assertThat(sql.getValue())
                .contains("FROM \"mn\".product_consumer pc")
                .endsWith("ORDER BY pc.product_id, pc.consumer_id");
    }

    @Test
    void subscriptionMapper_mapsEveryColumn() throws SQLException {
        repository.findSubscriptions();
        verify(jdbc).query(anyString(), subscriptionMapper.capture());
        when(row.getLong("id")).thenReturn(21L);
        when(row.getLong("product_id")).thenReturn(11L);
        when(row.getLong("consumer_id")).thenReturn(5L);
        when(row.getTimestamp("granted_ts")).thenReturn(Timestamp.valueOf(GRANTED));
        when(row.getBigDecimal("validity")).thenReturn(new BigDecimal("30"));
        when(row.getString("schedule_type")).thenReturn("interval");
        when(row.getString("schedule_expression")).thenReturn("PT1H");

        NetworkMapDTO.Subscription subscription = subscriptionMapper.getValue().mapRow(row, 0);

        assertThat(subscription.id()).isEqualTo(21L);
        assertThat(subscription.productId()).isEqualTo(11L);
        assertThat(subscription.consumerId()).isEqualTo(5L);
        assertThat(subscription.grantedAt()).isEqualTo(GRANTED);
        assertThat(subscription.validity()).isEqualByComparingTo("30");
        assertThat(subscription.scheduleType()).isEqualTo("interval");
        assertThat(subscription.scheduleExpression()).isEqualTo("PT1H");
    }

    @Test
    void subscriptionMapper_withNullGrantedTimestamp_hasNoGrantedAt() throws SQLException {
        repository.findSubscriptions();
        verify(jdbc).query(anyString(), subscriptionMapper.capture());
        when(row.getTimestamp("granted_ts")).thenReturn(null);

        assertThat(subscriptionMapper.getValue().mapRow(row, 0).grantedAt()).isNull();
    }

    @Test
    void withNoSchemaConfigured_tablesAreUnqualified() {
        new NetworkMapRepository(jdbc, new DiscoverySchema("")).findSubscriptions();

        verify(jdbc).query(sql.capture(), subscriptionMapper.capture());
        assertThat(sql.getValue()).contains("FROM product_consumer pc").doesNotContain("\"");
    }
}
