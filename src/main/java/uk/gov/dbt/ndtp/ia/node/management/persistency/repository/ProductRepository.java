/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Product;

/**
 * Repository interface for managing {@link Product} entities.
 *
 * This interface extends {@link JpaRepository} to provide CRUD operations and additional
 * query methods to interact with the {@link Product} database entity. It focuses on enabling
 * functionality specific to retrieving products based on identifiers or associated producers.
 *
 * The primary focus is on the {@link Product} entity with the identifier type {@link Long}.
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Retrieves a list of {@link Product} entities based on the provided list of product IDs.
     *
     * @param ids a list of product IDs for which the {@link Product} entities are to be retrieved
     * @return a list of {@link Product} entities matching the provided IDs
     */
    @Query("SELECT o FROM Product o " + "JOIN FETCH o.productType t " + "WHERE o.id IN :ids")
    List<Product> findByIds(List<Long> ids);

    /**
     * Retrieves a list of {@link Product} entities associated with the specified producer IDs.
     *
     * @param producers a list of producer IDs whose associated {@link Product} entities need to be retrieved
     * @return a list of {@link Product} entities linked to the specified producer IDs
     */
    @Query("SELECT o FROM Product o " + "JOIN FETCH o.productType t " + " WHERE o.producer.id IN :producers")
    List<Product> findByProducerIds(List<Long> producers);

    /**
     * Discovery candidate query: products across all organisations matching the optional
     * search filters (case-insensitive contains on name/topic, exact match on type name).
     * A {@code null} filter matches everything for that attribute. Not organisation-scoped -
     * policy (the PDP), not org membership, decides visibility for discovery. Uses a LEFT
     * JOIN on productType (unlike the other queries here) since type is optional and a
     * product without one must still be a candidate when no type filter is supplied.
     *
     * @param name optional case-insensitive contains filter on product name
     * @param topic optional case-insensitive contains filter on product topic
     * @param type optional case-insensitive exact filter on product type name
     * @param pageable bounds the candidate set size (e.g. {@code PageRequest.of(0, maxCandidates)})
     * @return candidate products matching the filters, bounded by {@code pageable}
     */
    default List<Product> findDiscoveryCandidates(String name, String topic, String type, Pageable pageable) {
        return findDiscoveryCandidatesByPattern(containsPattern(name), containsPattern(topic), type, pageable);
    }

    /**
     * Backing query for {@link #findDiscoveryCandidates}. Takes pre-built, LIKE-escaped
     * {@code %pattern%} strings (see {@link #containsPattern}) rather than raw filter values,
     * so the LIKE wildcards {@code %}/{@code _} in caller-supplied input are matched
     * literally, not interpreted as wildcards.
     */
    @Query("SELECT p FROM Product p "
            + "LEFT JOIN FETCH p.productType t "
            + "WHERE (:namePattern IS NULL OR LOWER(p.name) LIKE LOWER(:namePattern) ESCAPE '\\') "
            + "AND (:topicPattern IS NULL OR LOWER(p.topic) LIKE LOWER(:topicPattern) ESCAPE '\\') "
            + "AND (:type IS NULL OR LOWER(t.name) = LOWER(:type)) "
            + "ORDER BY p.id")
    List<Product> findDiscoveryCandidatesByPattern(
            @Param("namePattern") String namePattern,
            @Param("topicPattern") String topicPattern,
            @Param("type") String type,
            Pageable pageable);

    /**
     * Builds a {@code %value%} LIKE pattern with the LIKE metacharacters {@code \}, {@code %}
     * and {@code _} in {@code value} escaped (backslash-escaped, matching the query's
     * {@code ESCAPE '\'} clause), so a search value containing them is matched literally
     * instead of as wildcards.
     */
    private static String containsPattern(String value) {
        if (value == null) {
            return null;
        }
        String escaped = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
