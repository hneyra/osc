package dev.osc.automation.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Repository
public class R2dbcOutboxRepository implements OutboxRepository {

    private final DatabaseClient db;
    private final ObjectMapper objectMapper;

    public R2dbcOutboxRepository(ConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        this.db = DatabaseClient.create(connectionFactory);
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<OutboxEvent> save(OutboxEvent event) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(event.payload()))
                .flatMap(payloadJson ->
                        db.sql("""
                                INSERT INTO outbox_event
                                    (tenant_id, aggregate_type, aggregate_id, event_type, payload, status, attempts)
                                VALUES (:tenantId, :aggregateType, :aggregateId, :eventType, :payload::jsonb, 'PENDING', 0)
                                RETURNING id, created_at
                                """)
                                .bind("tenantId", event.tenantId())
                                .bind("aggregateType", event.aggregateType())
                                .bind("aggregateId", event.aggregateId())
                                .bind("eventType", event.eventType())
                                .bind("payload", payloadJson)
                                .map(row -> new OutboxEvent(
                                        row.get("id", UUID.class),
                                        event.tenantId(),
                                        event.aggregateType(),
                                        event.aggregateId(),
                                        event.eventType(),
                                        event.payload(),
                                        OutboxEventStatus.PENDING,
                                        0,
                                        row.get("created_at", Instant.class),
                                        null))
                                .one()
                );
    }

    @Override
    public Flux<OutboxEvent> findPending(int limit) {
        return db.sql("""
                SELECT id, tenant_id, aggregate_type, aggregate_id, event_type,
                       payload, status, attempts, created_at, processed_at
                FROM   outbox_event
                WHERE  status = 'PENDING'
                ORDER  BY created_at
                LIMIT  :limit
                """)
                .bind("limit", limit)
                .map(row -> {
                    try {
                        String payloadJson = row.get("payload", String.class);
                        Map<String, Object> payload = objectMapper.readValue(
                                payloadJson, new TypeReference<>() {});
                        return new OutboxEvent(
                                row.get("id", UUID.class),
                                row.get("tenant_id", UUID.class),
                                row.get("aggregate_type", String.class),
                                row.get("aggregate_id", UUID.class),
                                row.get("event_type", String.class),
                                payload,
                                OutboxEventStatus.valueOf(row.get("status", String.class)),
                                row.get("attempts", Integer.class),
                                row.get("created_at", Instant.class),
                                row.get("processed_at", Instant.class));
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to deserialize outbox event", e);
                    }
                })
                .all();
    }

    @Override
    public Mono<Void> markProcessed(UUID id) {
        return db.sql("UPDATE outbox_event SET status = 'PROCESSED', processed_at = now() WHERE id = :id")
                .bind("id", id)
                .then();
    }

    @Override
    public Mono<Void> markFailed(UUID id) {
        return db.sql("UPDATE outbox_event SET status = 'FAILED' WHERE id = :id")
                .bind("id", id)
                .then();
    }

    @Override
    public Mono<Void> incrementAttempts(UUID id) {
        return db.sql("UPDATE outbox_event SET attempts = attempts + 1 WHERE id = :id")
                .bind("id", id)
                .then();
    }
}
