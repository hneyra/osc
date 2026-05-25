package dev.osc.automation.dsl;

/** Thrown when a DSL expression contains a disallowed construct. */
public class DslSecurityException extends RuntimeException {
    public DslSecurityException(String message) {
        super(message);
    }
}
