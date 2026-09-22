/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeScopeCode;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.DiscoverySchema;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.ProductSearchQuery;

/**
 * The database access of product discovery. It uses plain SQL through
 * {@link NamedParameterJdbcTemplate} rather than JPA entities, for two reasons: the search
 * statement is assembled at run time from a policy decision, and loading an entity would read
 * every column - including the ones policy withholds.
 *
 * <p>Every method that loads related data takes the ids of a whole page, so a search costs a fixed
 * number of statements however many products it returns.
 *
 * <h2>Why the concatenated SQL here is not an injection risk</h2>
 *
 * <p>SonarCloud raises "Make sure using a dynamically formatted SQL query is safe here" on the
 * statements below. It is a security hotspot to review rather than a defect, and the review is
 * this:
 *
 * <ul>
 *   <li><b>The only interpolated text is the schema prefix.</b> Nothing a caller sends is ever
 *       concatenated into a statement. Every caller-supplied value is a bound parameter, and even
 *       a policy attribute <em>name</em> is bound rather than interpolated.
 *   <li><b>The prefix cannot carry a payload.</b> It comes from the
 *       {@code spring.jpa.properties.hibernate.default_schema} property, not from a request, and
 *       {@link DiscoverySchema} rejects anything that is not {@code [A-Za-z0-9_-]+} before quoting
 *       it. A value containing a quote, a space, a semicolon or a comment marker fails startup.
 *   <li><b>An identifier cannot be bound.</b> JDBC binds values, never table or schema names, so a
 *       schema-qualified statement has to be assembled as text. There is no parameterised form of
 *       this to move to.
 * </ul>
 *
 * <p>The search statement itself is assembled in {@code ProductSearchQueryBuilder} from a closed
 * registry of column names ({@code ProductField}), not from caller input.
 */
@Repository
public class ProductDiscoveryRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final String schema;

    public ProductDiscoveryRepository(NamedParameterJdbcTemplate jdbc, DiscoverySchema schema) {
        this.jdbc = jdbc;
        this.schema = schema.prefix();
    }

    /** One row per product of the page, keyed by the column aliases of {@link ProductSearchQuery}. */
    public List<Map<String, Object>> findPage(ProductSearchQuery query) {
        return jdbc.queryForList(query.pageSql(), query.parameters());
    }

    public long count(ProductSearchQuery query) {
        Long total = jdbc.queryForObject(query.countSql(), query.parameters(), Long.class);
        return total == null ? 0 : total;
    }

    /**
     * The live policy attributes of several entities of one scope.
     *
     * @param maskedNames attribute names to leave out; they are excluded in the query itself
     * @param excludeSensitive whether to leave out attributes flagged sensitive in the catalogue
     * @return entity id to (attribute name to value). A multi-valued attribute is a list; numbers
     *     and booleans keep their type. Entities without attributes are absent.
     */
    public Map<Long, Map<String, Object>> findAttributes(
            PolicyAttributeScopeCode scope,
            Collection<Long> entityIds,
            Set<String> maskedNames,
            boolean excludeSensitive) {
        if (entityIds.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource parameters =
                new MapSqlParameterSource().addValue("scope", scope.code()).addValue("ids", entityIds);
        StringBuilder sql = new StringBuilder("SELECT a.entity_id, a.name, a.multi_valued, a.item, a.item_type"
                + " FROM " + schema + "policy_attribute_live_value a"
                + " WHERE a.scope_code = :scope AND a.entity_id IN (:ids)");
        if (!maskedNames.isEmpty()) {
            sql.append(" AND a.name NOT IN (:masked)");
            parameters.addValue("masked", maskedNames);
        }
        if (excludeSensitive) {
            sql.append(" AND a.sensitive = FALSE");
        }
        sql.append(" ORDER BY a.entity_id, a.name, a.item");

        Map<Long, Map<String, Object>> attributesByEntity = new LinkedHashMap<>();
        jdbc.query(sql.toString(), parameters, row -> {
            Map<String, Object> attributes =
                    attributesByEntity.computeIfAbsent(row.getLong("entity_id"), id -> new LinkedHashMap<>());
            addValue(
                    attributes,
                    row.getString("name"),
                    row.getBoolean("multi_valued"),
                    typed(row.getString("item"), row.getString("item_type")));
        });
        return attributesByEntity;
    }

    /** The names of the attributes the catalogue flags sensitive, whatever their scope. */
    public Set<String> findSensitiveAttributeNames() {
        String sql = "SELECT DISTINCT name FROM " + schema + "policy_attribute_definition"
                + " WHERE sensitive = TRUE AND is_deleted = FALSE";
        return Set.copyOf(jdbc.queryForList(sql, Map.of(), String.class));
    }

    /** Every grant on the given products, with its consumer and the consumer's organisation. */
    public List<Grant> findGrants(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT pc.id AS grant_id, pc.product_id, pc.granted_ts, pc.validity,"
                + " pc.schedule_type, pc.schedule_expression,"
                + " c.id AS consumer_id, c.name AS consumer_name,"
                + " o.organisation_key, o.name AS organisation_name"
                + " FROM " + schema + "product_consumer pc"
                + " JOIN " + schema + "consumer c ON c.id = pc.consumer_id"
                + " JOIN " + schema + "organisation o ON o.id = c.org_id"
                + " WHERE pc.product_id IN (:ids)"
                + " ORDER BY pc.product_id, c.name";
        return jdbc.query(
                sql,
                Map.of("ids", productIds),
                (row, index) -> new Grant(
                        row.getLong("grant_id"),
                        row.getLong("product_id"),
                        toLocalDateTime(row.getTimestamp("granted_ts")),
                        row.getBigDecimal("validity"),
                        row.getString("schedule_type"),
                        row.getString("schedule_expression"),
                        row.getLong("consumer_id"),
                        row.getString("consumer_name"),
                        row.getString("organisation_key"),
                        row.getString("organisation_name")));
    }

    /**
     * The organisations using the given products: those with at least one grant. A grant row is
     * access; its validity plays no part. Reads nothing about the individual consumers, so it can
     * be shown to a caller who may not see them.
     */
    public List<SubscribingOrganisation> findSubscribingOrganisations(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT pc.product_id, o.organisation_key, o.name AS organisation_name,"
                + " COUNT(DISTINCT c.id) AS consumers, MIN(pc.granted_ts) AS since"
                + " FROM " + schema + "product_consumer pc"
                + " JOIN " + schema + "consumer c ON c.id = pc.consumer_id"
                + " JOIN " + schema + "organisation o ON o.id = c.org_id"
                + " WHERE pc.product_id IN (:ids)"
                + " GROUP BY pc.product_id, o.organisation_key, o.name"
                + " ORDER BY pc.product_id, o.organisation_key";
        return jdbc.query(
                sql,
                Map.of("ids", productIds),
                (row, index) -> new SubscribingOrganisation(
                        row.getLong("product_id"),
                        row.getString("organisation_key"),
                        row.getString("organisation_name"),
                        row.getInt("consumers"),
                        toLocalDateTime(row.getTimestamp("since"))));
    }

    /** One grant ({@code product_consumer} row) with its consumer and the consumer's organisation. */
    public record Grant(
            long grantId,
            long productId,
            LocalDateTime grantedAt,
            BigDecimal validity,
            String scheduleType,
            String scheduleExpression,
            long consumerId,
            String consumerName,
            String organisationKey,
            String organisationName) {}

    /** One organisation using one product. */
    public record SubscribingOrganisation(
            long productId, String organisationKey, String organisationName, int consumers, LocalDateTime since) {}

    /** A multi-valued attribute collects its values; a single-valued one keeps the first. */
    @SuppressWarnings("unchecked")
    private static void addValue(Map<String, Object> attributes, String name, boolean multiValued, Object value) {
        if (multiValued) {
            ((List<Object>) attributes.computeIfAbsent(name, key -> new ArrayList<>())).add(value);
        } else {
            attributes.putIfAbsent(name, value);
        }
    }

    private static Object typed(String item, String itemType) {
        if (item == null) {
            return null;
        }
        return switch (itemType == null ? "" : itemType) {
            case "number" -> new BigDecimal(item);
            case "boolean" -> Boolean.valueOf(item);
            default -> item;
        };
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
