package dev.osc.automation.engine;

/** Result of user code execution. Either output or error is non-null, never both. */
public record UserCodeResult(Object output, String error) {
    public static UserCodeResult success(Object output) { return new UserCodeResult(output, null); }
    public static UserCodeResult failure(String error)  { return new UserCodeResult(null, error); }
}
