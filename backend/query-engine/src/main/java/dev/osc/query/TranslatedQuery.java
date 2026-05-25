package dev.osc.query;

import dev.osc.metadata.FieldDefinition;

import java.util.List;

/**
 * Result of QueryTranslator — a parameterized SQL string and its bind values.
 * sql uses positional $1, $2, … placeholders (R2DBC PostgreSQL style).
 */
public record TranslatedQuery(
        String sql,
        List<Object> bindings,
        List<FieldDefinition> selectedFields
) {
    public TranslatedQuery {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("sql must not be blank");
        if (bindings == null) throw new IllegalArgumentException("bindings must not be null");
        if (selectedFields == null) throw new IllegalArgumentException("selectedFields must not be null");
    }
}
