package dev.osc.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.osc.automation.dsl.FormulaEvaluator;
import dev.osc.automation.dsl.FormulaParser;
import dev.osc.metadata.FieldDefinition;
import dev.osc.metadata.FieldType;
import dev.osc.metadata.TenantContext;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * R2DBC adapter for QueryExecutor.
 * Executes a pre-translated (parameterized) SQL query against PostgreSQL.
 * Tenant is already baked into bindings[0] by QueryTranslator.
 * No .block() — fully reactive end-to-end.
 */
@Component
public class R2dbcQueryExecutor implements QueryExecutor {

    private final DatabaseClient client;
    private final ObjectMapper objectMapper;
    private final FormulaParser formulaParser;
    private final FormulaEvaluator formulaEvaluator;

    public R2dbcQueryExecutor(DatabaseClient client) {
        this(client, new ObjectMapper());
    }

    public R2dbcQueryExecutor(DatabaseClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.formulaParser = new FormulaParser();
        this.formulaEvaluator = new FormulaEvaluator();
    }

    @Override
    public Flux<Map<String, Object>> execute(TranslatedQuery query) {
        return bindAll(client.sql(query.sql()), query.bindings())
                .map((row, meta) -> mapRow(row, query.selectedFields()))
                .all()
                .onErrorMap(ex -> new QueryExecutionException("Query execution failed", ex));
    }

    @Override
    public Mono<Long> count(TranslatedQuery query) {
        // Wrap the query as a COUNT subquery
        String countSql = "SELECT COUNT(*) FROM (" + query.sql() + ") AS _q";
        return bindAll(client.sql(countSql), query.bindings())
                .map((row, meta) -> {
                    Object val = row.get(0);
                    return val instanceof Number n ? n.longValue() : 0L;
                })
                .one()
                .defaultIfEmpty(0L)
                .onErrorMap(ex -> new QueryExecutionException("Count query failed", ex));
    }

    private DatabaseClient.GenericExecuteSpec bindAll(DatabaseClient.GenericExecuteSpec spec,
                                                       List<Object> bindings) {
        for (int i = 0; i < bindings.size(); i++) {
            Object val = bindings.get(i);
            if (val == null) {
                spec = spec.bindNull(i, Object.class);
            } else {
                spec = spec.bind(i, val);
            }
        }
        return spec;
    }

    private Map<String, Object> mapRow(Row row, List<FieldDefinition> fields) {
        Map<String, Object> context = new HashMap<>();
        boolean hasFormula = fields.stream().anyMatch(f -> f.fieldType() == FieldType.FORMULA);
        if (hasFormula) {
            try {
                String nameVal = row.get("___record_name___", String.class);
                if (nameVal != null) {
                    context.put("name", nameVal);
                }
            } catch (Exception ignored) {}
            try {
                UUID ownerIdVal = row.get("___record_owner_id___", UUID.class);
                if (ownerIdVal != null) {
                    context.put("owner_id", ownerIdVal);
                }
            } catch (Exception ignored) {}
            try {
                String dataJson = row.get("___record_data___", String.class);
                if (dataJson != null && !dataJson.isBlank()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> dataMap = objectMapper.readValue(dataJson, Map.class);
                    if (dataMap != null) {
                        context.putAll(dataMap);
                    }
                }
            } catch (Exception ignored) {}
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (FieldDefinition field : fields) {
            if (field.fieldType() != FieldType.FORMULA) {
                Object value = readTyped(row, field);
                result.put(field.apiName(), value);
                context.put(field.apiName(), value);
            }
        }

        for (FieldDefinition field : fields) {
            if (field.fieldType() == FieldType.FORMULA) {
                Object value = null;
                try {
                    String formula = null;
                    String configStr = field.config();
                    if (configStr != null && !configStr.isBlank()) {
                        com.fasterxml.jackson.databind.JsonNode configNode = objectMapper.readTree(configStr);
                        if (configNode.has("formula")) {
                            formula = configNode.get("formula").asText();
                        }
                    }
                    if (formula != null) {
                        var ast = formulaParser.parse(formula);
                        value = formulaEvaluator.evaluate(ast, context);
                    }
                } catch (Exception e) {
                    // Graceful fallback to null on error
                }
                result.put(field.apiName(), value);
                context.put(field.apiName(), value);
            }
        }

        return Collections.unmodifiableMap(result);
    }

    private Object readTyped(Row row, FieldDefinition field) {
        String alias = field.apiName();
        return switch (field.fieldType()) {
            case NUMBER, CURRENCY, PERCENT -> row.get(alias, BigDecimal.class);
            case DATE                      -> row.get(alias, LocalDate.class);
            case DATETIME                  -> row.get(alias, OffsetDateTime.class);
            case BOOLEAN                   -> row.get(alias, Boolean.class);
            case LOOKUP, MASTER_DETAIL     -> row.get(alias, UUID.class);
            default                        -> row.get(alias, String.class);
        };
    }
}
