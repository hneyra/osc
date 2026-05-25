package dev.osc.query;

public enum QueryOperator {
    EQ("="), NEQ("!="), LT("<"), GT(">"), LTE("<="), GTE(">="),
    LIKE("LIKE"), IN("IN"), NOT_IN("NOT IN");

    private final String symbol;

    QueryOperator(String symbol) { this.symbol = symbol; }

    public String symbol() { return symbol; }
}
