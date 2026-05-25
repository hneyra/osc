package dev.osc.api.record;

import dev.osc.metadata.TenantContext;
import dev.osc.persistence.DynamicPersistenceService;
import dev.osc.persistence.ObjectNotFoundException;
import dev.osc.persistence.PageRequest;
import dev.osc.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Dynamic REST controller — routes CRUD + SOQL-like queries to any object
 * defined in metadata. No hardcoded entity-specific endpoints.
 *
 * Endpoints:
 *   GET    /api/v1/data/{objectApiName}           → list records
 *   POST   /api/v1/data/{objectApiName}           → create record
 *   GET    /api/v1/data/{objectApiName}/{id}      → get record by ID
 *   PATCH  /api/v1/data/{objectApiName}/{id}      → update record
 *   DELETE /api/v1/data/{objectApiName}/{id}      → delete record
 *   POST   /api/v1/data/{objectApiName}/query     → SOQL-like query
 */
@RestController
@RequestMapping("/api/v1/data")
public class DynamicRecordController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final DynamicPersistenceService persistenceService;
    private final QueryParser queryParser;
    private final QueryTranslator queryTranslator;
    private final QueryExecutor queryExecutor;

    public DynamicRecordController(DynamicPersistenceService persistenceService,
                                    QueryParser queryParser,
                                    QueryTranslator queryTranslator,
                                    QueryExecutor queryExecutor) {
        this.persistenceService = persistenceService;
        this.queryParser = queryParser;
        this.queryTranslator = queryTranslator;
        this.queryExecutor = queryExecutor;
    }

    // ── GET /{objectApiName} — list records ───────────────────────────────────

    @GetMapping("/{objectApiName}")
    public Mono<RecordResponse> listRecords(
            @PathVariable String objectApiName,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        int safeLimit = Math.min(limit, MAX_LIMIT);
        PageRequest page = new PageRequest(offset / Math.max(safeLimit, 1), safeLimit);

        return persistenceService.listRecords(objectApiName, page)
                .map(r -> recordToMap(r))
                .collectList()
                .map(rows -> new RecordResponse(rows, rows.size(), safeLimit, offset, objectApiName))
                .onErrorMap(ObjectNotFoundException.class,
                        ex -> new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage()));
    }

    // ── POST /{objectApiName} — create record ─────────────────────────────────

    @PostMapping("/{objectApiName}")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> createRecord(
            @PathVariable String objectApiName,
            @RequestBody Map<String, Object> body) {

        return persistenceService.createRecord(objectApiName, body)
                .map(this::recordToMap)
                .onErrorMap(ObjectNotFoundException.class,
                        ex -> new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage()))
                .onErrorMap(dev.osc.persistence.FieldValidationException.class,
                        ex -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage()));
    }

    // ── GET /{objectApiName}/{id} — get by ID ─────────────────────────────────

    @GetMapping("/{objectApiName}/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> getRecord(
            @PathVariable String objectApiName,
            @PathVariable UUID id) {

        return persistenceService.getRecord(id)
                .map(r -> ResponseEntity.ok(recordToMap(r)))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    // ── PATCH /{objectApiName}/{id} — update record ───────────────────────────

    @PatchMapping("/{objectApiName}/{id}")
    public Mono<Map<String, Object>> updateRecord(
            @PathVariable String objectApiName,
            @PathVariable UUID id,
            @RequestBody Map<String, Object> patch) {

        return persistenceService.updateRecord(id, patch)
                .map(this::recordToMap)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    // ── DELETE /{objectApiName}/{id} — delete record ──────────────────────────

    @DeleteMapping("/{objectApiName}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteRecord(
            @PathVariable String objectApiName,
            @PathVariable UUID id) {

        return persistenceService.deleteRecord(id);
    }

    // ── POST /{objectApiName}/query — SOQL-like query ─────────────────────────

    @PostMapping("/{objectApiName}/query")
    public Mono<RecordResponse> query(
            @PathVariable String objectApiName,
            @RequestBody QueryRequest request) {

        return Mono.deferContextual(ctx -> {
            UUID tenantId = UUID.fromString((String) ctx.get(TenantContext.TENANT_ID_KEY));
            SelectQuery ast;
            try {
                ast = queryParser.parse(request.query());
            } catch (ParseException ex) {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage()));
            }

            return queryTranslator.translate(ast, tenantId, Set.of())
                    .flatMap(translated ->
                            queryExecutor.execute(translated).collectList()
                                    .zipWith(queryExecutor.count(translated))
                                    .map(tuple -> new RecordResponse(
                                            tuple.getT1(),
                                            tuple.getT2(),
                                            ast.limit() != null ? ast.limit() : DEFAULT_LIMIT,
                                            ast.offset() != null ? ast.offset() : 0,
                                            objectApiName
                                    ))
                    )
                    .onErrorMap(ObjectNotFoundException.class,
                            ex -> new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage()))
                    .onErrorMap(FieldNotFoundException.class,
                            ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage()));
        });
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> recordToMap(dev.osc.persistence.RecordEntity r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", r.id().toString());
        map.put("objectId", r.objectId().toString());
        if (r.name() != null) map.put("name", r.name());
        if (r.ownerId() != null) map.put("ownerId", r.ownerId().toString());
        if (r.data() != null) map.putAll(r.data());
        if (r.createdAt() != null) map.put("createdAt", r.createdAt().toString());
        if (r.updatedAt() != null) map.put("updatedAt", r.updatedAt().toString());
        return Collections.unmodifiableMap(map);
    }

    /** Request body for the /query endpoint. */
    public record QueryRequest(String query) {}
}
