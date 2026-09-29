/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.model.policy.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A predicate over products, as a tree. It is the one language both sides of a search speak: a
 * policy's {@code row_filter} arrives in it, caller criteria are turned into it, and
 * {@code SqlPredicateCompiler} translates it to SQL.
 *
 * <pre>
 * {"type": "group", "combinator": "and", "nodes": [
 *   {"type": "comparison", "attribute": "identifiability", "operator": "in", "values": ["anonymised"]},
 *   {"type": "comparison", "field": "organisation.key", "operator": "eq", "values": ["ENV"]}
 * ]}
 * </pre>
 *
 * <p>An unknown {@code type} or operator fails to bind, which the policy client reports as
 * unreadable details and therefore DENY: a filter that cannot be understood is never ignored.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = FilterNode.Group.class, name = "group"),
    @JsonSubTypes.Type(value = FilterNode.Comparison.class, name = "comparison"),
    @JsonSubTypes.Type(value = FilterNode.Literal.class, name = "literal")
})
public sealed interface FilterNode {

    /** Matches no product. What an absent or refused filter reads as. */
    Literal DENY_ALL = new Literal(false);

    /** Matches every product. Only ever used when policy enforcement is switched off. */
    Literal ALLOW_ALL = new Literal(true);

    /** Several nodes joined by AND or OR. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Group(@JsonProperty("combinator") Combinator combinator, @JsonProperty("nodes") List<FilterNode> nodes)
            implements FilterNode {

        public Group {
            if (combinator == null) {
                throw new IllegalArgumentException("A filter group needs a combinator");
            }
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
        }

        public static Group and(List<? extends FilterNode> nodes) {
            return new Group(Combinator.AND, new ArrayList<>(nodes));
        }
    }

    /**
     * One comparison of a product field or a policy attribute with some values.
     *
     * @param target the field or attribute compared
     * @param operator the comparison to make
     * @param values the operand values; scalars
     */
    record Comparison(FilterTarget target, ComparisonOperator operator, List<Object> values) implements FilterNode {

        public Comparison {
            if (target == null) {
                throw new IllegalArgumentException("A comparison needs a field or an attribute");
            }
            if (operator == null) {
                throw new IllegalArgumentException(
                        "The comparison on '" + target.qualifiedName() + "' needs an operator");
            }
            values = values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
        }

        /**
         * Reads the wire form, in which exactly one of {@code field} and {@code attribute} names the
         * target. The name may be qualified ({@code organisation.key}) instead of carrying a
         * {@code scope}, which is how a policy writes it.
         */
        @JsonCreator
        static Comparison fromJson(
                @JsonProperty("scope") FilterScope scope,
                @JsonProperty("field") String field,
                @JsonProperty("attribute") String attribute,
                @JsonProperty("operator") ComparisonOperator operator,
                @JsonProperty("values") List<Object> values) {
            if ((field == null) == (attribute == null)) {
                throw new IllegalArgumentException("A comparison names exactly one of 'field' and 'attribute'");
            }
            FilterTarget target = FilterTarget.parse(scope, field != null, field != null ? field : attribute);
            return new Comparison(target, operator, values);
        }

        public static Comparison ofField(String name, ComparisonOperator operator, Object... values) {
            return new Comparison(FilterTarget.ofField(name), operator, List.of(values));
        }

        public static Comparison ofAttribute(String name, ComparisonOperator operator, Object... values) {
            return new Comparison(FilterTarget.ofAttribute(name), operator, List.of(values));
        }
    }

    /** A constant: every product, or none. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Literal(@JsonProperty("value") boolean value) implements FilterNode {}
}
