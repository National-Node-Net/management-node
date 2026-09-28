/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.network.NetworkMapDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.DiscoverySchema;

/**
 * The five reads the network map is built from.
 *
 * Five flat statements rather than one join, and that is the point rather than a shortcut. A
 * single query spanning organisation, producer, consumer, product and product_consumer would be
 * a four-way fan-out: every product repeated once per subscriber, every producer once per
 * product. The client would then have to de-duplicate it back into entities, which is the work
 * these five queries avoid by never doing it. Five indexed reads of a few hundred rows each also
 * cost less than one cartesian product of them.
 *
 * JDBC rather than JPA, following {@link ProductDiscoveryRepository}: this is a read-only
 * projection into DTOs with no entity graph to manage, and going through Hibernate would only
 * add lazy-loading decisions to a query that wants none.
 *
 * The schema prefix comes from {@link DiscoverySchema}, which validates it, so none of the SQL
 * below interpolates anything a caller can influence.
 */
@Repository
public class NetworkMapRepository {

    private final NamedParameterJdbcTemplate jdbc;

    private final String organisationsSql;
    private final String producersSql;
    private final String consumersSql;
    private final String productsSql;
    private final String subscriptionsSql;

    public NetworkMapRepository(NamedParameterJdbcTemplate jdbc, DiscoverySchema schema) {
        this.jdbc = jdbc;
        final String prefix = schema.prefix();

        // Only organisations that actually appear on the network. One with no producer and no
        // consumer has no place in a graph of who exchanges data with whom, and drawing it
        // would be an isolated dot nobody can act on.
        this.organisationsSql = "SELECT o.id, o.organisation_key, o.name"
                + " FROM " + prefix + "organisation o"
                + " WHERE EXISTS (SELECT 1 FROM " + prefix + "producer p WHERE p.org_id = o.id)"
                + "    OR EXISTS (SELECT 1 FROM " + prefix + "consumer c WHERE c.org_id = o.id)"
                + " ORDER BY o.organisation_key";

        this.producersSql = "SELECT pr.id, pr.name, pr.description, pr.active, o.organisation_key"
                + " FROM " + prefix + "producer pr"
                + " JOIN " + prefix + "organisation o ON o.id = pr.org_id"
                + " ORDER BY o.organisation_key, pr.name";

        this.consumersSql = "SELECT c.id, c.name, c.is_default, c.schedule_type, c.schedule_expression,"
                + " o.organisation_key"
                + " FROM " + prefix + "consumer c"
                + " JOIN " + prefix + "organisation o ON o.id = c.org_id"
                + " ORDER BY o.organisation_key, c.name";

        // The product type is joined left: `product_type_id` is nullable, and a product with no
        // type recorded must still appear. Reading it as an inner join is how a product silently
        // disappears from a map that claims to be complete.
        this.productsSql = "SELECT p.id, p.name, p.description, p.topic, p.source,"
                + " pt.name AS type, pr.id AS producer_id, o.organisation_key"
                + " FROM " + prefix + "product p"
                + " JOIN " + prefix + "producer pr ON pr.id = p.producer_id"
                + " JOIN " + prefix + "organisation o ON o.id = pr.org_id"
                + " LEFT JOIN " + prefix + "product_type pt ON pt.id = p.product_type_id"
                + " ORDER BY p.name";

        this.subscriptionsSql = "SELECT pc.id, pc.product_id, pc.consumer_id, pc.granted_ts,"
                + " pc.validity, pc.schedule_type, pc.schedule_expression"
                + " FROM " + prefix + "product_consumer pc"
                + " ORDER BY pc.product_id, pc.consumer_id";
    }

    public List<NetworkMapDTO.Organisation> findOrganisations() {
        return jdbc.query(organisationsSql, ORGANISATION);
    }

    public List<NetworkMapDTO.Producer> findProducers() {
        return jdbc.query(producersSql, PRODUCER);
    }

    public List<NetworkMapDTO.Consumer> findConsumers() {
        return jdbc.query(consumersSql, CONSUMER);
    }

    public List<NetworkMapDTO.Product> findProducts() {
        return jdbc.query(productsSql, PRODUCT);
    }

    public List<NetworkMapDTO.Subscription> findSubscriptions() {
        return jdbc.query(subscriptionsSql, SUBSCRIPTION);
    }

    private static final RowMapper<NetworkMapDTO.Organisation> ORGANISATION =
            (row, index) -> NetworkMapDTO.Organisation.builder()
                    .id(row.getLong("id"))
                    .key(row.getString("organisation_key"))
                    .name(row.getString("name"))
                    .build();

    private static final RowMapper<NetworkMapDTO.Producer> PRODUCER = (row, index) -> NetworkMapDTO.Producer.builder()
            .id(row.getLong("id"))
            .name(row.getString("name"))
            .description(row.getString("description"))
            .active(nullableBoolean(row, "active"))
            .organisationKey(row.getString("organisation_key"))
            .build();

    private static final RowMapper<NetworkMapDTO.Consumer> CONSUMER = (row, index) -> NetworkMapDTO.Consumer.builder()
            .id(row.getLong("id"))
            .name(row.getString("name"))
            .organisationKey(row.getString("organisation_key"))
            .defaultConsumer(nullableBoolean(row, "is_default"))
            .scheduleType(row.getString("schedule_type"))
            .scheduleExpression(row.getString("schedule_expression"))
            .build();

    private static final RowMapper<NetworkMapDTO.Product> PRODUCT = (row, index) -> NetworkMapDTO.Product.builder()
            .id(row.getLong("id"))
            .name(row.getString("name"))
            .description(row.getString("description"))
            .topic(row.getString("topic"))
            .type(row.getString("type"))
            .source(row.getString("source"))
            .producerId(row.getLong("producer_id"))
            .organisationKey(row.getString("organisation_key"))
            .build();

    private static final RowMapper<NetworkMapDTO.Subscription> SUBSCRIPTION =
            (row, index) -> NetworkMapDTO.Subscription.builder()
                    .id(row.getLong("id"))
                    .productId(row.getLong("product_id"))
                    .consumerId(row.getLong("consumer_id"))
                    .grantedAt(toLocalDateTime(row.getTimestamp("granted_ts")))
                    .validity(row.getBigDecimal("validity"))
                    .scheduleType(row.getString("schedule_type"))
                    .scheduleExpression(row.getString("schedule_expression"))
                    .build();

    /**
     * A boolean that may be NULL.
     *
     * {@code getBoolean} reports NULL as {@code false}, which for {@code active} would turn "we
     * do not know" into "it is switched off" - a different and more alarming claim.
     */
    private static Boolean nullableBoolean(ResultSet row, String column) throws SQLException {
        final boolean value = row.getBoolean(column);
        return row.wasNull() ? null : value;
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
