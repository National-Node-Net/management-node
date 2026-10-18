/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductFilterOptionsResponseDTO.Value;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.ComparisonOperator;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.filter.FilterTarget;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository.FacetCount;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.FacetValues.Summary;

/**
 * Covers {@link FacetValues#summarise}: the null row becomes {@code missingCount}, field targets
 * merge case variants and attribute targets do not, a BOOLEAN target always lists both booleans,
 * everything else is ordered by count then value and capped at {@link FacetValues#CAP}, and
 * {@code selected} is matched case-insensitively.
 */
class FacetValuesTest {

    private static final FilterOptionTarget FORMAT_FIELD = field(ProductField.FORMAT, "TEXT");
    private static final FilterOptionTarget ATTRIBUTE = attribute("quality_designation", "TEXT");
    private static final FilterOptionTarget IS_OWN_PRODUCT = field(ProductField.IS_OWN_PRODUCT, "BOOLEAN");

    @Test
    void nullRowBecomesMissingCount() {
        Summary summary = FacetValues.summarise(
                FORMAT_FIELD, List.of(new FacetCount("format", null, 7), new FacetCount("format", "CSV", 3)), Set.of());

        assertThat(summary.missingCount()).isEqualTo(7);
        assertThat(summary.values()).extracting(Value::value).containsExactly("CSV");
    }

    @Test
    void valuesOrderByCountThenValue() {
        Summary summary = FacetValues.summarise(
                FORMAT_FIELD,
                List.of(
                        new FacetCount("format", "JSON", 3),
                        new FacetCount("format", "CSV", 4),
                        new FacetCount("format", "XML", 3)),
                Set.of());

        assertThat(summary.values())
                .extracting(Value::value, Value::count)
                .containsExactly(tuple("CSV", 4L), tuple("JSON", 3L), tuple("XML", 3L));
    }

    @Test
    void zeroCountValuesAreKept() {
        Summary summary = FacetValues.summarise(
                FORMAT_FIELD,
                List.of(new FacetCount("format", "CSV", 4), new FacetCount("format", "XML", 0)),
                Set.of());

        assertThat(summary.values()).extracting(Value::value, Value::count).contains(tuple("XML", 0L));
    }

    @Test
    void allZero_whenNothingMatches_valuesStillListed() {
        Summary summary = FacetValues.summarise(
                FORMAT_FIELD,
                List.of(new FacetCount("format", "CSV", 0), new FacetCount("format", "XML", 0)),
                Set.of());

        assertThat(summary.values()).hasSize(2);
        assertThat(summary.values()).extracting(Value::count).containsOnly(0L);
    }

    @Test
    void fieldCaseVariantsMerge_underTheCommonestSpelling() {
        Summary summary = FacetValues.summarise(
                FORMAT_FIELD,
                List.of(new FacetCount("format", "CSV", 4), new FacetCount("format", "csv", 1)),
                Set.of());

        assertThat(summary.values()).extracting(Value::value, Value::count).containsExactly(tuple("CSV", 5L));
    }

    @Test
    void attributeCaseVariantsDoNotMerge() {
        Summary summary = FacetValues.summarise(
                ATTRIBUTE,
                List.of(
                        new FacetCount("quality_designation", "Validated", 1),
                        new FacetCount("quality_designation", "validated", 2)),
                Set.of());

        assertThat(summary.values()).hasSize(2);
        assertThat(summary.values())
                .extracting(Value::value, Value::count)
                .containsExactlyInAnyOrder(tuple("Validated", 1L), tuple("validated", 2L));
    }

    @Test
    void cutAtFifty_marksTruncated() {
        List<FacetCount> rows = IntStream.range(0, 51)
                .mapToObj(i -> new FacetCount("format", "value" + i, 1))
                .toList();

        Summary summary = FacetValues.summarise(FORMAT_FIELD, rows, Set.of());

        assertThat(summary.values()).hasSize(50);
        assertThat(summary.truncated()).isTrue();
    }

    @Test
    void booleanTargetAlwaysListsTrueThenFalseAsBooleans() {
        Summary summary =
                FacetValues.summarise(IS_OWN_PRODUCT, List.of(new FacetCount("isOwnProduct", "true", 2)), Set.of());

        assertThat(summary.values())
                .extracting(Value::value, Value::count)
                .containsExactly(tuple(Boolean.TRUE, 2L), tuple(Boolean.FALSE, 0L));
    }

    @Test
    void selectedComparesCaseInsensitively() {
        Summary summary =
                FacetValues.summarise(FORMAT_FIELD, List.of(new FacetCount("format", "CSV", 4)), Set.of("csv"));

        assertThat(summary.values()).extracting(Value::selected).containsExactly(true);
    }

    private static FilterOptionTarget field(ProductField productField, String dataType) {
        return new FilterOptionTarget(
                "field:" + productField.apiName(),
                FilterTarget.ofField(productField.apiName()),
                productField,
                productField.apiName(),
                dataType,
                false,
                List.of(ComparisonOperator.EQ),
                ComparisonOperator.EQ,
                true,
                true);
    }

    private static FilterOptionTarget attribute(String name, String dataType) {
        return new FilterOptionTarget(
                "attribute:product." + name,
                FilterTarget.ofAttribute(name),
                null,
                name,
                dataType,
                false,
                List.of(ComparisonOperator.EQ),
                ComparisonOperator.EQ,
                true,
                true);
    }
}
