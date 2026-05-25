package dev.osc.query;

/** A single field comparison: field operator value. */
public record Condition(FieldRef field, QueryOperator operator, Literal value) implements QueryNode {
    public Condition {
        if (field == null) throw new IllegalArgumentException("field must not be null");
        if (operator == null) throw new IllegalArgumentException("operator must not be null");
        if (value == null) throw new IllegalArgumentException("value must not be null");
    }
}
