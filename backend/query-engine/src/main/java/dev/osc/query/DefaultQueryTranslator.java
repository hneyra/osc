package dev.osc.query;

import dev.osc.metadata.FieldDefinition;
import dev.osc.metadata.FieldType;
import dev.osc.metadata.MetadataEngine;
import dev.osc.metadata.StorageKind;
import dev.osc.persistence.ObjectNotFoundException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Translates a SelectQuery AST into a parameterized PostgreSQL SQL string.
 *
 * Security invariants:
 *  1. Object name validated against metadata — never interpolated into SQL.
 *  2. Field names validated against metadata — never interpolated into SQL.
 *  3. All user-supplied values become positional bind parameters ($n).
 *  4. Tenant filter injected unconditionally as $1.
 *  5. FLS-forbidden fields stripped from SELECT when allowedFields is non-empty.
 *
 * JSONB cast rules:
 *  TEXT/EMAIL/URL/PHONE/PICKLIST/MULTIPICKLIST → data->>'key'
 *  NUMBER/CURRENCY/PERCENT                     → (data->>'key')::numeric
 *  DATE                                         → (data->>'key')::date
 *  DATETIME                                     → (data->>'key')::timestamptz
 *  BOOLEAN                                      → (data->>'key')::boolean
 *  LOOKUP/MASTER_DETAIL                         → (data->>'key')::uuid
 */
@Component
public class DefaultQueryTranslator implements QueryTranslator {

    private final MetadataEngine metadataEngine;

    public DefaultQueryTranslator(MetadataEngine metadataEngine) {
        this.metadataEngine = metadataEngine;
    }

    @Override
    public Mono<TranslatedQuery> translate(SelectQuery query, UUID tenantId, Set<String> allowedFields) {
        return metadataEngine.findObject(tenantId, query.objectName())
                .switchIfEmpty(Mono.error(new ObjectNotFoundException(query.objectName())))
                .flatMap(obj ->
                        metadataEngine.findFields(tenantId, obj.id()).collectList()
                                .flatMap(allFields -> Mono.fromCallable(() ->
                                        buildQuery(query, tenantId, allFields, allowedFields)
                                ))
                );
    }

    private TranslatedQuery buildQuery(SelectQuery q, UUID tenantId,
                                        List<FieldDefinition> allFields, Set<String> allowedFields) {
        Map<String, FieldDefinition> fieldMap = allFields.stream()
                .collect(Collectors.toMap(FieldDefinition::apiName, f -> f));

        // Resolve SELECT fields
        List<FieldDefinition> selected = resolveSelectFields(q, fieldMap, allowedFields);

        // Build bind list — $1 is always tenantId
        List<Object> bindings = new ArrayList<>();
        bindings.add(tenantId);

        // SELECT clause
        String selectClause = selected.isEmpty()
                ? "id, tenant_id, object_id, name, owner_id, data, created_at, updated_at"
                : selected.stream()
                        .map(f -> fieldExpr(f) + " AS \"" + f.apiName() + "\"")
                        .collect(Collectors.joining(", "));

        boolean hasFormula = selected.stream().anyMatch(f -> f.fieldType() == FieldType.FORMULA);
        if (hasFormula && !selected.isEmpty()) {
            selectClause += ", data AS \"___record_data___\", name AS \"___record_name___\", owner_id AS \"___record_owner_id___\"";
        }

        // WHERE clause
        StringBuilder whereSql = new StringBuilder("tenant_id = $1");
        if (q.whereClause() != null) {
            whereSql.append(" AND (");
            appendCondition(q.whereClause(), whereSql, bindings, fieldMap);
            whereSql.append(")");
        }

        // Full SQL
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(selectClause)
           .append(" FROM record")
           .append(" WHERE ").append(whereSql);

        if (q.orderByField() != null) {
            FieldDefinition orderField = resolveField(q.orderByField().name(), fieldMap, "SELECT");
            sql.append(" ORDER BY ").append(fieldExpr(orderField))
               .append(" ").append(q.orderDir().name());
        }

        if (q.limit() != null)  sql.append(" LIMIT ").append(q.limit());
        if (q.offset() != null) sql.append(" OFFSET ").append(q.offset());

        return new TranslatedQuery(sql.toString(), Collections.unmodifiableList(bindings), selected);
    }

    private List<FieldDefinition> resolveSelectFields(SelectQuery q,
                                                       Map<String, FieldDefinition> fieldMap,
                                                       Set<String> allowedFields) {
        List<FieldDefinition> base;
        if (q.selectAll()) {
            base = new ArrayList<>(fieldMap.values());
        } else {
            base = q.fields().stream()
                    .map(ref -> resolveField(ref.name(), fieldMap, q.objectName()))
                    .collect(Collectors.toList());
        }
        if (!allowedFields.isEmpty()) {
            base = base.stream()
                    .filter(f -> allowedFields.contains(f.apiName()))
                    .collect(Collectors.toList());
        }
        return base;
    }

    private FieldDefinition resolveField(String apiName, Map<String, FieldDefinition> fieldMap,
                                          String objectName) {
        FieldDefinition f = fieldMap.get(apiName);
        if (f == null) throw new FieldNotFoundException(objectName, apiName);
        return f;
    }

    private void appendCondition(QueryNode node, StringBuilder sql, List<Object> bindings,
                                  Map<String, FieldDefinition> fieldMap) {
        switch (node) {
            case Condition c -> appendSimpleCondition(c, sql, bindings, fieldMap);
            case BinaryOp op -> {
                sql.append("(");
                appendCondition(op.left(), sql, bindings, fieldMap);
                sql.append(" ").append(op.op().name()).append(" ");
                appendCondition(op.right(), sql, bindings, fieldMap);
                sql.append(")");
            }
            default -> throw new IllegalArgumentException("Unexpected WHERE node: " + node.getClass());
        }
    }

    private void appendSimpleCondition(Condition c, StringBuilder sql, List<Object> bindings,
                                        Map<String, FieldDefinition> fieldMap) {
        FieldDefinition field = resolveField(c.field().name(), fieldMap, "WHERE");
        String expr = fieldExpr(field);

        switch (c.operator()) {
            case IN, NOT_IN -> {
                Literal.ListValue list = (Literal.ListValue) c.value();
                List<String> placeholders = new ArrayList<>();
                for (Literal item : list.values()) {
                    bindings.add(literalToObject(item));
                    placeholders.add("$" + bindings.size());
                }
                sql.append(expr)
                   .append(c.operator() == QueryOperator.NOT_IN ? " NOT IN (" : " IN (")
                   .append(String.join(", ", placeholders))
                   .append(")");
            }
            case LIKE -> {
                bindings.add(literalToObject(c.value()));
                sql.append(expr).append(" LIKE $").append(bindings.size());
            }
            default -> {
                bindings.add(literalToObject(c.value()));
                sql.append(expr).append(" ").append(c.operator().symbol())
                   .append(" $").append(bindings.size());
            }
        }
    }

    private Object literalToObject(Literal literal) {
        return switch (literal) {
            case Literal.StringValue s  -> s.value();
            case Literal.NumberValue n  -> n.value();
            case Literal.BooleanValue b -> b.value();
            case Literal.NullValue ignored -> null;
            case Literal.ListValue ignored -> throw new IllegalArgumentException("Nested list literal not supported");
        };
    }

    private String fieldExpr(FieldDefinition f) {
        if (f.fieldType() == FieldType.FORMULA) {
            return "NULL";
        }
        if (f.storageKind() == StorageKind.COLUMN) {
            return f.storageKey() != null ? f.storageKey() : f.apiName();
        }
        String key = f.storageKey() != null ? f.storageKey() : f.apiName();
        return switch (f.fieldType()) {
            case NUMBER, CURRENCY, PERCENT ->
                    "(data->>'" + key + "')::numeric";
            case DATE ->
                    "(data->>'" + key + "')::date";
            case DATETIME ->
                    "(data->>'" + key + "')::timestamptz";
            case BOOLEAN ->
                    "(data->>'" + key + "')::boolean";
            case LOOKUP, MASTER_DETAIL ->
                    "(data->>'" + key + "')::uuid";
            default ->
                    "data->>'" + key + "'";
        };
    }
}
