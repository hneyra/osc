package dev.osc.query;

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

    public R2dbcQueryExecutor(DatabaseClient client) {
        this.client = client;
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
        Map<String, Object> result = new LinkedHashMap<>();
        for (FieldDefinition field : fields) {
            Object value = readTyped(row, field);
            result.put(field.apiName(), value);
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
