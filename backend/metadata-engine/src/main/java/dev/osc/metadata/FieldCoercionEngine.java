package dev.osc.metadata;

/**
 * Primary port — validates and coerces raw API input to typed domain values.
 * Pure function: no I/O, no side effects, deterministic.
 */
public interface FieldCoercionEngine {

    /**
     * Coerces {@code rawValue} to the canonical type for {@code field}.
     * Returns {@link CoercionResult.Success} with a typed value, or
     * {@link CoercionResult.Failure} with a human-readable error.
     */
    CoercionResult coerce(FieldDefinition field, Object rawValue);
}
