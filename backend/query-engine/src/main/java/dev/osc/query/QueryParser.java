package dev.osc.query;

/**
 * Primary port — parses a SOQL-like query string into a SelectQuery AST.
 * Pure parsing: no database access, no metadata validation.
 */
public interface QueryParser {

    /**
     * Parses the query string.
     *
     * @throws ParseException if the query does not conform to the grammar
     */
    SelectQuery parse(String query);
}
