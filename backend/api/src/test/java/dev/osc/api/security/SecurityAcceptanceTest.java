package dev.osc.api.security;

import dev.osc.api.OscApplication;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Security acceptance tests — Issue #39 / re-enabled by Issue #78.
 *
 * Verifies that a field is NEVER visible without the appropriate field permission,
 * across all access vectors: list, get-by-id, and SOQL query.
 *
 * Setup (seeded by {@link #seedPermissionFixtures()} against the Testcontainers DB):
 *   - Tenant: system tenant (00000000-0000-0000-0000-000000000001)
 *   - User A: permission set with can_read + can_create on Account, NO field permissions
 *             → unrestricted mode → all fields pass
 *   - User B: permission set with can_read on Account AND field permissions granting
 *             every Account field EXCEPT industry__c (explicit can_read = false)
 *             → industry__c must be stripped from every response
 *   - User C: NO permission set → canRead returns false → 403
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = OscApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecurityAcceptanceTest {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("osc_test")
                    .withUsername("osc")
                    .withPassword("test");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () ->
                POSTGRES.getJdbcUrl().replace("jdbc:", "r2dbc:"));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    // ── Test fixtures ─────────────────────────────────────────────────────────

    private static final String TENANT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String ACCOUNT_OBJECT_ID = "00000000-0000-0000-0001-000000000001";

    // User A — object permission only, no field permissions (unrestricted FLS)
    private static final String USER_A_ID = "aaaaaaaa-0000-0000-0000-000000000000";
    private static final String PS_A_ID = "a0000000-0000-0000-0000-00000000000a";
    // User B — field permissions that deny "industry__c"
    private static final String USER_B_ID = "bbbbbbbb-0000-0000-0000-000000000000";
    private static final String PS_B_ID = "b0000000-0000-0000-0000-00000000000b";
    // User C — no permission set at all
    private static final String USER_C_ID = "cccccccc-0000-0000-0000-000000000000";

    private static final AtomicBoolean SEEDED = new AtomicBoolean(false);

    @BeforeEach
    void initClientAndSeed() throws Exception {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
        seedPermissionFixtures();
    }

    /**
     * Seeds the permission-set fixtures once, after the Spring context (and therefore
     * the Flyway migrations) is up. Plain JDBC: blocking is fine in tests, and the
     * PostgreSQL driver is already on the test runtime classpath via the persistence module.
     */
    private static void seedPermissionFixtures() throws Exception {
        if (!SEEDED.compareAndSet(false, true)) {
            return;
        }
        String sql = """
                INSERT INTO md_permission_set (id, tenant_id, api_name, label)
                VALUES ('%PS_A%', '%TENANT%', 'test_ps_user_a', 'Test PS - User A (object only)')
                ON CONFLICT (tenant_id, api_name) DO NOTHING;

                INSERT INTO md_permission_set (id, tenant_id, api_name, label)
                VALUES ('%PS_B%', '%TENANT%', 'test_ps_user_b', 'Test PS - User B (FLS deny industry__c)')
                ON CONFLICT (tenant_id, api_name) DO NOTHING;

                INSERT INTO md_object_permission
                    (tenant_id, permission_set_id, object_id, can_read, can_create, can_edit, can_delete)
                VALUES ('%TENANT%', '%PS_A%', '%ACCOUNT%', TRUE, TRUE, FALSE, FALSE)
                ON CONFLICT (permission_set_id, object_id) DO NOTHING;

                INSERT INTO md_object_permission
                    (tenant_id, permission_set_id, object_id, can_read, can_create, can_edit, can_delete)
                VALUES ('%TENANT%', '%PS_B%', '%ACCOUNT%', TRUE, FALSE, FALSE, FALSE)
                ON CONFLICT (permission_set_id, object_id) DO NOTHING;

                INSERT INTO md_field_permission
                    (tenant_id, permission_set_id, field_id, field_api_name, can_read, can_edit)
                SELECT f.tenant_id, '%PS_B%', f.id, f.api_name, f.api_name <> 'industry__c', FALSE
                FROM   md_field f
                WHERE  f.tenant_id = '%TENANT%' AND f.object_id = '%ACCOUNT%'
                ON CONFLICT (permission_set_id, field_id) DO NOTHING;

                INSERT INTO md_user_permission_set (tenant_id, user_id, permission_set_id)
                VALUES ('%TENANT%', '%USER_A%', '%PS_A%')
                ON CONFLICT (tenant_id, user_id, permission_set_id) DO NOTHING;

                INSERT INTO md_user_permission_set (tenant_id, user_id, permission_set_id)
                VALUES ('%TENANT%', '%USER_B%', '%PS_B%')
                ON CONFLICT (tenant_id, user_id, permission_set_id) DO NOTHING;
                """
                .replace("%TENANT%", TENANT_ID)
                .replace("%ACCOUNT%", ACCOUNT_OBJECT_ID)
                .replace("%PS_A%", PS_A_ID)
                .replace("%PS_B%", PS_B_ID)
                .replace("%USER_A%", USER_A_ID)
                .replace("%USER_B%", USER_B_ID);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    // ── Vector 1: list records ────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("[list] user with no field permissions sees all fields")
    void list_userWithNoFieldPermissions_seesAllFields() {
        webTestClient.get()
                .uri("/api/v1/data/Account")
                .header("X-Tenant-ID", TENANT_ID)
                .header("X-User-ID", USER_A_ID)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data").isArray();
    }

    @Test
    @Order(2)
    @DisplayName("[list] user with field-level deny on 'industry__c' never sees that field")
    void list_userWithFlsDenyOnIndustry_fieldStripped() {
        webTestClient.get()
                .uri("/api/v1/data/Account")
                .header("X-Tenant-ID", TENANT_ID)
                .header("X-User-ID", USER_B_ID)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[*].industry__c").doesNotExist();
    }

    @Test
    @Order(3)
    @DisplayName("[list] user with no permission set gets 403")
    void list_userWithNoPermissionSet_gets403() {
        webTestClient.get()
                .uri("/api/v1/data/Account")
                .header("X-Tenant-ID", TENANT_ID)
                .header("X-User-ID", USER_C_ID)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isForbidden();
    }

    // ── Vector 2: get by ID ────────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("[get-by-id] forbidden field is not present in single record response")
    void getById_forbiddenFieldStripped() {
        // Create a record as User A (can_create granted, FLS unrestricted)
        Map<?, ?> created = webTestClient.post()
                .uri("/api/v1/data/Account")
                .header("X-Tenant-ID", TENANT_ID)
                .header("X-User-ID", USER_A_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Acme Corp", "industry__c", "Technology"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();

        Assertions.assertNotNull(created);
        String recordId = String.valueOf(created.get("id"));

        // User A sees the field it just wrote
        webTestClient.get()
                .uri("/api/v1/data/Account/" + recordId)
                .header("X-Tenant-ID", TENANT_ID)
                .header("X-User-ID", USER_A_ID)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("Acme Corp")
                .jsonPath("$.industry__c").isEqualTo("Technology");

        // Fetched as User B — industry__c must be stripped, the rest visible
        webTestClient.get()
                .uri("/api/v1/data/Account/" + recordId)
                .header("X-Tenant-ID", TENANT_ID)
                .header("X-User-ID", USER_B_ID)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("Acme Corp")
                .jsonPath("$.industry__c").doesNotExist();
    }

    // ── Vector 3: SOQL query ───────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("[query] forbidden field is stripped from SOQL query results")
    void query_forbiddenFieldStripped() {
        var queryBody = """
                {"query":"SELECT name, industry__c FROM Account"}
                """;

        webTestClient.post()
                .uri("/api/v1/data/Account/query")
                .header("X-Tenant-ID", TENANT_ID)
                .header("X-User-ID", USER_B_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(queryBody)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[*].industry__c").doesNotExist();
    }

    @Test
    @Order(6)
    @DisplayName("[query] user with no permission set gets 403 on SOQL query")
    void query_userWithNoPermissionSet_gets403() {
        var queryBody = """
                {"query":"SELECT name FROM Account"}
                """;

        webTestClient.post()
                .uri("/api/v1/data/Account/query")
                .header("X-Tenant-ID", TENANT_ID)
                .header("X-User-ID", USER_C_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(queryBody)
                .exchange()
                .expectStatus().isForbidden();
    }

    // ── Missing headers ────────────────────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("missing X-User-ID header returns 401")
    void missingUserIdHeader_returns401() {
        webTestClient.get()
                .uri("/api/v1/data/Account")
                .header("X-Tenant-ID", TENANT_ID)
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
