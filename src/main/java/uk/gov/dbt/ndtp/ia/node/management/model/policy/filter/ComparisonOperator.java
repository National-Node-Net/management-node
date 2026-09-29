/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.model.policy.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Locale;

/**
 * The comparisons a {@link FilterNode.Comparison} can make, in policy row filters and in caller
 * criteria alike. Adding one means adding it here and teaching {@code SqlPredicateCompiler} to
 * translate it; nothing else changes.
 *
 * <p>An operator is <em>negative</em> when it holds for a product that does not carry the
 * attribute at all ("none of these tags" is true of a product with no tags). Every other operator
 * requires the attribute to be present, so a missing attribute never widens a result.
 */
public enum ComparisonOperator {
    EQ(Operands.ONE, false),
    NEQ(Operands.ONE, true),
    IN(Operands.MANY, false),
    NOT_IN(Operands.MANY, true),
    ANY_OF(Operands.MANY, false),
    ALL_OF(Operands.MANY, false),
    NONE_OF(Operands.MANY, true),
    LT(Operands.ONE, false),
    LTE(Operands.ONE, false),
    GT(Operands.ONE, false),
    GTE(Operands.ONE, false),
    CONTAINS(Operands.ONE, false),
    EXISTS(Operands.NONE, false),
    NOT_EXISTS(Operands.NONE, true);

    /** How many operand values an operator takes. */
    public enum Operands {
        NONE,
        ONE,
        MANY
    }

    private final Operands operands;
    private final boolean negative;

    ComparisonOperator(Operands operands, boolean negative) {
        this.operands = operands;
        this.negative = negative;
    }

    public Operands operands() {
        return operands;
    }

    /** Whether the operator holds when the attribute is absent. */
    public boolean isNegative() {
        return negative;
    }

    /** Whether the operator compares magnitudes, and so needs numeric operands. */
    public boolean isOrdering() {
        return this == LT || this == LTE || this == GT || this == GTE;
    }

    /** Whether {@code count} operand values is acceptable for this operator. */
    public boolean accepts(int count) {
        return switch (operands) {
            case NONE -> count == 0;
            case ONE -> count == 1;
            case MANY -> true;
        };
    }

    @JsonValue
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ComparisonOperator fromWireName(String value) {
        for (ComparisonOperator operator : values()) {
            if (operator.wireName().equalsIgnoreCase(value)) {
                return operator;
            }
        }
        throw new IllegalArgumentException("Unsupported operator '" + value + "'; supported operators are "
                + Arrays.stream(values()).map(ComparisonOperator::wireName).toList());
    }
}
