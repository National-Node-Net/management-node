/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.model.policy.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Covers the wire grammar of the predicate tree: that the {@code row_filter} a rule really returns
 * binds, that a qualified name carries its scope, and that anything the grammar does not define -
 * an unknown node type, operator or combinator, or a comparison naming both or neither of
 * {@code field} and {@code attribute} - fails to bind rather than being read as something else.
 */
class FilterNodeSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private FilterNode read(String json) throws JsonProcessingException {
        return objectMapper.readValue(json, FilterNode.class);
    }

    /** The shape {@code policies.product.discover} returns as its row filter. */
    @Test
    void read_rowFilterFromThePolicy_bindsTheWholeTree() throws Exception {
        FilterNode node = read(
                """
                {"type": "group", "combinator": "and", "nodes": [
                  {"type": "comparison", "attribute": "identifiability", "operator": "in",
                   "values": ["anonymised", "aggregated"]},
                  {"type": "group", "combinator": "or", "nodes": [
                    {"type": "comparison", "field": "organisation.key", "operator": "eq", "values": ["ENV"]},
                    {"type": "literal", "value": false}
                  ]}
                ]}""");

        assertThat(node).isInstanceOf(FilterNode.Group.class);
        FilterNode.Group group = (FilterNode.Group) node;
        assertThat(group.combinator()).isEqualTo(Combinator.AND);
        assertThat(group.nodes()).hasSize(2);

        FilterNode.Comparison attribute = (FilterNode.Comparison) group.nodes().get(0);
        assertThat(attribute.target()).isEqualTo(new FilterTarget(FilterScope.PRODUCT, false, "identifiability"));
        assertThat(attribute.operator()).isEqualTo(ComparisonOperator.IN);
        assertThat(attribute.values()).containsExactly("anonymised", "aggregated");

        FilterNode.Group inner = (FilterNode.Group) group.nodes().get(1);
        assertThat(inner.combinator()).isEqualTo(Combinator.OR);
        assertThat(inner.nodes().get(1)).isEqualTo(FilterNode.DENY_ALL);

        FilterNode.Comparison field = (FilterNode.Comparison) inner.nodes().get(0);
        assertThat(field.target().field()).isTrue();
        assertThat(field.target().scope()).isEqualTo(FilterScope.ORGANISATION);
        assertThat(field.target().name()).isEqualTo("key");
        assertThat(field.target().qualifiedName()).isEqualTo("organisation.key");
    }

    /** A bare name with an explicit scope means the same as the qualified name a policy writes. */
    @Test
    void read_explicitScopeWithABareName_bindsLikeTheQualifiedName() throws Exception {
        FilterNode.Comparison scoped = (FilterNode.Comparison)
                read("{\"type\": \"comparison\", \"scope\": \"organisation\", \"field\": \"key\","
                        + " \"operator\": \"eq\", \"values\": [\"ENV\"]}");
        FilterNode.Comparison qualified =
                (FilterNode.Comparison) read("{\"type\": \"comparison\", \"field\": \"organisation.key\","
                        + " \"operator\": \"eq\", \"values\": [\"ENV\"]}");

        assertThat(scoped).isEqualTo(qualified);
        assertThat(scoped.target()).isEqualTo(new FilterTarget(FilterScope.ORGANISATION, true, "key"));
    }

    @Test
    void read_comparisonWithoutAScope_isAboutTheProduct() throws Exception {
        FilterNode.Comparison comparison = (FilterNode.Comparison)
                read("{\"type\": \"comparison\", \"field\": \"topic\", \"operator\": \"eq\", \"values\": [\"a\"]}");

        assertThat(comparison.target()).isEqualTo(new FilterTarget(FilterScope.PRODUCT, true, "topic"));
        assertThat(comparison.target().qualifiedName()).isEqualTo("topic");
    }

    @Test
    void read_comparisonNamingBothFieldAndAttribute_failsToBind() {
        assertThatThrownBy(() -> read("{\"type\": \"comparison\", \"field\": \"topic\", \"attribute\": \"topic\","
                        + " \"operator\": \"eq\", \"values\": [\"a\"]}"))
                .isInstanceOf(JsonProcessingException.class)
                .hasMessageContaining("exactly one of 'field' and 'attribute'");
    }

    @Test
    void read_comparisonNamingNeitherFieldNorAttribute_failsToBind() {
        assertThatThrownBy(() -> read("{\"type\": \"comparison\", \"operator\": \"eq\", \"values\": [\"a\"]}"))
                .isInstanceOf(JsonProcessingException.class)
                .hasMessageContaining("exactly one of 'field' and 'attribute'");
    }

    @Test
    void read_comparisonWithoutAnOperator_failsToBind() {
        assertThatThrownBy(() -> read("{\"type\": \"comparison\", \"field\": \"topic\", \"values\": [\"a\"]}"))
                .isInstanceOf(JsonProcessingException.class)
                .hasMessageContaining("needs an operator");
    }

    @Test
    void read_unknownNodeType_failsToBind() {
        assertThatThrownBy(() -> read("{\"type\": \"regex\", \"pattern\": \".*\"}"))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void read_unknownOperator_failsToBind() {
        assertThatThrownBy(() -> read("{\"type\": \"comparison\", \"field\": \"topic\","
                        + " \"operator\": \"matches\", \"values\": [\"a\"]}"))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void read_unknownCombinator_failsToBind() {
        assertThatThrownBy(() -> read("{\"type\": \"group\", \"combinator\": \"xor\", \"nodes\": []}"))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void read_comparisonWithoutValues_hasNoValues() throws Exception {
        FilterNode.Comparison comparison = (FilterNode.Comparison)
                read("{\"type\": \"comparison\", \"attribute\": \"identifiability\", \"operator\": \"exists\"}");

        assertThat(comparison.values()).isEmpty();
    }

    @Test
    void read_groupWithoutNodes_hasNoNodes() throws Exception {
        FilterNode.Group group = (FilterNode.Group) read("{\"type\": \"group\", \"combinator\": \"or\"}");

        assertThat(group.nodes()).isEmpty();
    }

    @Test
    void read_groupWithUnknownProperties_ignoresThem() throws Exception {
        FilterNode.Group group =
                (FilterNode.Group) read("{\"type\": \"group\", \"combinator\": \"and\", \"nodes\": [], \"id\": 7}");

        assertThat(group.combinator()).isEqualTo(Combinator.AND);
    }

    @Test
    void write_literal_roundTrips() throws Exception {
        String json = objectMapper.writerFor(FilterNode.class).writeValueAsString(FilterNode.ALLOW_ALL);

        assertThat(json).isEqualTo("{\"type\":\"literal\",\"value\":true}");
        assertThat(read(json)).isEqualTo(FilterNode.ALLOW_ALL);
    }
}
