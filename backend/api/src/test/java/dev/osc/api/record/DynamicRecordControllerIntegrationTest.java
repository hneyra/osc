package dev.osc.api.record;

import dev.osc.api.OscApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * End-to-end integration tests for the dynamic REST controller — issue #25.
 *
 * <p>Boots the full application ({@code RANDOM_PORT}) against a real PostgreSQL (Testcontainers;
 * the app runs the production Flyway migrations on startup, seeding the standard {@code Account}
 * object). Exercises all six endpoints over HTTP against the seeded {@code Account}, using the
 * {@code X-Tenant-ID} + {@code X-User-ID} headers (the Phase-1/4 stand-ins for the JWT).</p>
 *
 * <p>A permission set granting full CRUD on {@code Account} (and <em>no</em> field permissions →
 * unrestricted reads) is seeded for the test user, so the requests are authorized. Requires Docker.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = OscApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("DynamicRecordController end-to-end (all 6 endpoints, real app + PostgreSQL)")
class DynamicRecordControllerIntegrationTest {

    private static final String TENANT_ID = "00000000-0000-0000-0000-000000000001";
    private static final UUID TENANT_UUID = UUID.fromString(TENANT_ID);
    private static final String USER_ID = "dddddddd-0000-0000-0000-000000000000";

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("osc_ctrl_test")
                    .withUsername("osc")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> POSTGRES.getJdbcUrl().replace("jdbc:", "r2dbc:"));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    DatabaseClient db;

    WebTestClient http;

    @BeforeEach
    void setUp() {
        http = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
        seedFullCrudPermission();
    }

    /** Grants the test user full CRUD on Account via a permission set (idempotent). No field perms. */
    private void seedFullCrudPermission() {
        UUID accountId = db.sql("SELECT id FROM md_object WHERE tenant_id = :t AND api_name = 'Account'")
                .bind("t", TENANT_UUID)
                .map((row, meta) -> row.get("id", UUID.class)).one().block();

        UUID psId = db.sql("""
                        INSERT INTO md_permission_set (tenant_id, api_name, label)
                        VALUES (:t, 'it_full_crud', 'IT Full CRUD')
                        ON CONFLICT (tenant_id, api_name) DO UPDATE SET label = EXCLUDED.label
                        RETURNING id
                        """)
                .bind("t", TENANT_UUID)
                .map((row, meta) -> row.get("id", UUID.class)).one().block();

        db.sql("""
                        INSERT INTO md_object_permission
                            (tenant_id, permission_set_id, object_id, can_read, can_create, can_edit, can_delete)
                        VALUES (:t, :ps, :o, TRUE, TRUE, TRUE, TRUE)
                        ON CONFLICT (permission_set_id, object_id) DO NOTHING
                        """)
                .bind("t", TENANT_UUID).bind("ps", psId).bind("o", accountId)
                .fetch().rowsUpdated().block();

        db.sql("""
                        INSERT INTO md_user_permission_set (tenant_id, user_id, permission_set_id)
                        VALUES (:t, :u, :ps)
                        ON CONFLICT (tenant_id, user_id, permission_set_id) DO NOTHING
                        """)
                .bind("t", TENANT_UUID).bind("u", UUID.fromString(USER_ID)).bind("ps", psId)
                .fetch().rowsUpdated().block();
    }

    private WebTestClient.RequestHeadersSpec<?> authGet(String uri) {
        return http.get().uri(uri).header("X-Tenant-ID", TENANT_ID).header("X-User-ID", USER_ID)
                .accept(MediaType.APPLICATION_JSON);
    }

    private String extractId(String name, String industry) {
        return http.post().uri("/api/v1/data/Account")
                .header("X-Tenant-ID", TENANT_ID).header("X-User-ID", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", name, "industry__c", industry))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class)
                .returnResult().getResponseBody().get("id").toString();
    }

    // ── endpoints ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST creates a record (201) and GET by id returns it (200)")
    void createThenGetById() {
        String id = extractId("Acme Corp", "Technology");

        http.get().uri("/api/v1/data/Account/" + id)
                .header("X-Tenant-ID", TENANT_ID).header("X-User-ID", USER_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(id)
                .jsonPath("$.industry__c").isEqualTo("Technology");
    }

    @Test
    @DisplayName("GET list returns the envelope with a data array")
    void listReturnsEnvelope() {
        extractId("ListCo", "Finance");

        authGet("/api/v1/data/Account?limit=50&offset=0")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data").isArray()
                .jsonPath("$.objectApiName").isEqualTo("Account");
    }

    @Test
    @DisplayName("PATCH updates a record (200)")
    void patchUpdates() {
        String id = extractId("PatchCo", "Technology");

        http.patch().uri("/api/v1/data/Account/" + id)
                .header("X-Tenant-ID", TENANT_ID).header("X-User-ID", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("industry__c", "Finance"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.industry__c").isEqualTo("Finance");
    }

    @Test
    @DisplayName("POST /query runs a SOQL-like query and returns the envelope")
    void queryEndpoint() {
        extractId("QueryCo", "Aerospace");

        http.post().uri("/api/v1/data/Account/query")
                .header("X-Tenant-ID", TENANT_ID).header("X-User-ID", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", "SELECT industry__c FROM Account WHERE industry__c = 'Aerospace'"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data").isArray()
                .jsonPath("$.data[0].industry__c").isEqualTo("Aerospace");
    }

    @Test
    @DisplayName("DELETE removes a record (204) and a subsequent GET returns 404")
    void deleteThenGet404() {
        String id = extractId("DeleteCo", "Retail");

        http.delete().uri("/api/v1/data/Account/" + id)
                .header("X-Tenant-ID", TENANT_ID).header("X-User-ID", USER_ID)
                .exchange()
                .expectStatus().isNoContent();

        http.get().uri("/api/v1/data/Account/" + id)
                .header("X-Tenant-ID", TENANT_ID).header("X-User-ID", USER_ID)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("unknown objectApiName is rejected (403 — permission is checked before metadata)")
    void unknownObjectRejected() {
        // The controller checks canRead first; the user has no permission set on a non-existent
        // object, so the request fails closed with 403 (rather than the issue's nominal 404 — a
        // deliberate permission-first design). Either way, no data is returned.
        authGet("/api/v1/data/DoesNotExist")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @DisplayName("missing X-User-ID returns 401 (fail closed)")
    void missingUser_returns401() {
        http.get().uri("/api/v1/data/Account")
                .header("X-Tenant-ID", TENANT_ID)
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
