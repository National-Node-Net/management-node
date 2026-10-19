/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.ProductFilterOptionsResponseDTO.Value;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository.FacetCount;

/**
 * Turns one filter target's raw facet-count rows into the values the filter panel shows.
 *
 * <p>A row with a null value becomes {@code missingCount} rather than a value. A <b>field</b>
 * target (one whose {@link FilterOptionTarget#field()} is not null) merges rows whose values are
 * equal ignoring case, summing their counts under the spelling with the highest count - ties
 * broken by the lexicographically smallest spelling; an <b>attribute</b> target never merges. A
 * {@code BOOLEAN} target always lists both {@code true} and {@code false}, in that order, with a
 * count of zero for whichever has no row. Every other target is ordered by count descending, then
 * by value ascending case-insensitively, and cut at {@link #CAP}.
 *
 * <p>{@code selected} mirrors how discover matches the same filter: a field compares
 * case-insensitively ({@code LOWER(column)}), an attribute exactly ({@code a.item IN (...)}), so a
 * value is only marked selected when the caller's filter would actually match it.
 */
public final class FacetValues {

    /** The most values a single filter group lists before it reports {@code truncated}. */
    public static final int CAP = 50;

    private static final String BOOLEAN_DATA_TYPE = "BOOLEAN";

    private FacetValues() {}

    /**
     * @param values the values to show, in display order, already capped
     * @param missingCount how many matching products carry no value at all for this target
     * @param truncated whether more distinct values existed than {@link #CAP} allowed to be shown
     */
    public record Summary(List<Value> values, long missingCount, boolean truncated) {}

    /**
     * @param target the filter this summary is for
     * @param rows this target's own facet-count rows; a null {@link FacetCount#value()} means
     *     "products with no value"
     * @param selected the values the caller's current criteria already selected, as the caller
     *     sent them; matched case-insensitively for a field target and exactly for an attribute
     */
    public static Summary summarise(FilterOptionTarget target, List<FacetCount> rows, Set<String> selected) {
        long missingCount = 0;
        List<FacetCount> present = new ArrayList<>();
        for (FacetCount row : rows) {
            if (row.value() == null) {
                missingCount += row.count();
            } else {
                present.add(row);
            }
        }

        if (BOOLEAN_DATA_TYPE.equals(target.dataType())) {
            return summariseBoolean(present, missingCount, selectedMatcher(target, selected));
        }

        boolean mergeCase = target.field() != null;
        Predicate<String> isSelected = selectedMatcher(target, selected);
        Map<String, Map<String, Long>> groups = new LinkedHashMap<>();
        for (FacetCount row : present) {
            String spelling = row.value();
            String groupKey = mergeCase ? spelling.toLowerCase(Locale.ROOT) : spelling;
            groups.computeIfAbsent(groupKey, key -> new LinkedHashMap<>()).merge(spelling, row.count(), Long::sum);
        }

        List<Value> values = new ArrayList<>();
        for (Map<String, Long> spellings : groups.values()) {
            long total = spellings.values().stream().mapToLong(Long::longValue).sum();
            String winner = winningSpelling(spellings);
            values.add(new Value(winner, total, isSelected.test(winner)));
        }

        values.sort(Comparator.comparingLong(Value::count)
                .reversed()
                .thenComparing(value -> (String) value.value(), String.CASE_INSENSITIVE_ORDER));

        boolean truncated = values.size() > CAP;
        List<Value> capped = truncated ? values.subList(0, CAP) : values;
        return new Summary(List.copyOf(capped), missingCount, truncated);
    }

    private static Summary summariseBoolean(List<FacetCount> present, long missingCount, Predicate<String> isSelected) {
        long trueCount = 0;
        long falseCount = 0;
        for (FacetCount row : present) {
            if (Boolean.parseBoolean(row.value())) {
                trueCount += row.count();
            } else {
                falseCount += row.count();
            }
        }
        List<Value> values = List.of(
                new Value(Boolean.TRUE, trueCount, isSelected.test("true")),
                new Value(Boolean.FALSE, falseCount, isSelected.test("false")));
        return new Summary(values, missingCount, false);
    }

    /** The spelling with the highest count; ties go to the lexicographically smallest spelling. */
    private static String winningSpelling(Map<String, Long> spellings) {
        String winner = null;
        long winnerCount = -1;
        for (Map.Entry<String, Long> entry : spellings.entrySet()) {
            String spelling = entry.getKey();
            long count = entry.getValue();
            if (winner == null || count > winnerCount || (count == winnerCount && spelling.compareTo(winner) < 0)) {
                winner = spelling;
                winnerCount = count;
            }
        }
        return winner;
    }

    /**
     * Whether a facet value is one the caller selected: case-insensitively for a field, which
     * discover matches with {@code LOWER()}, and exactly for an attribute, which it matches with
     * {@code a.item IN (...)}.
     */
    private static Predicate<String> selectedMatcher(FilterOptionTarget target, Set<String> selected) {
        if (target.field() == null) {
            return selected::contains;
        }
        Set<String> lowered =
                selected.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        return value -> lowered.contains(value.toLowerCase(Locale.ROOT));
    }
}
