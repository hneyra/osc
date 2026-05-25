package dev.osc.metadata;

/**
 * Result of a field coercion attempt. Sealed so every callsite must handle both cases.
 * Success holds the typed value (null is valid for optional fields).
 * Failure holds a user-facing error message.
 */
public sealed interface CoercionResult permits CoercionResult.Success, CoercionResult.Failure {

    record Success(Object typedValue) implements CoercionResult {}

    record Failure(String error) implements CoercionResult {}

    static CoercionResult success(Object value) { return new Success(value); }
    static CoercionResult failure(String error)  { return new Failure(error); }

    default boolean isSuccess() { return this instanceof Success; }
    default boolean isFailure() { return this instanceof Failure; }
}
