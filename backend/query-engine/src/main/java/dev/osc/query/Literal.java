package dev.osc.query;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sealed literal value hierarchy.
 * Inner records live in the same compilation unit to satisfy the permits clause.
 */
public sealed interface Literal extends QueryNode
        permits Literal.StringValue, Literal.NumberValue, Literal.BooleanValue,
                Literal.NullValue, Literal.ListValue {

    record StringValue(String value) implements Literal {}

    record NumberValue(BigDecimal value) implements Literal {}

    record BooleanValue(boolean value) implements Literal {}

    record NullValue() implements Literal {}

    /** IN / NOT IN list: e.g. ('OPEN', 'PENDING'). */
    record ListValue(List<Literal> values) implements Literal {
        public ListValue {
            if (values == null || values.isEmpty())
                throw new IllegalArgumentException("IN list must not be empty");
        }
    }
}
