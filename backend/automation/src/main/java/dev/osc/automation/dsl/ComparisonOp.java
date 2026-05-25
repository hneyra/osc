package dev.osc.automation.dsl;

public enum ComparisonOp {
    EQ, NEQ, GT, GTE, LT, LTE;

    static ComparisonOp fromSymbol(String s) {
        return switch (s) {
            case "==" -> EQ;
            case "!=" -> NEQ;
            case ">"  -> GT;
            case ">=" -> GTE;
            case "<"  -> LT;
            case "<=" -> LTE;
            default -> throw new DslSecurityException("Unknown operator: " + s);
        };
    }
}
