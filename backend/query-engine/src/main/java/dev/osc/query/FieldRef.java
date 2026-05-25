package dev.osc.query;

/** Reference to a field in a query (apiName, case-preserved from source). */
public record FieldRef(String name) implements QueryNode {
    public FieldRef {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("FieldRef name must not be blank");
    }
}
