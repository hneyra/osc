package dev.osc.persistence;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL-level RLS verification for issue #18.
 *
 * <p>Proves that the {@code app.current_tenant} session variable — set through the exact
 * production mechanism, {@code SELECT set_config('app.current_tenant', $1, false)} — gates
 * rows via the {@code tenant_isolation} RLS policies created in V1, with <b>no</b>
 * application-level {@code WHERE tenant_id = ...} filter in the queries under test.
 * This is the database-side guarantee that backs {@code TenantContextFilter}'s
 * Reactor-Context propagation.</p>
 *
 * <p>Crucial detail: the Testcontainers bootstrap user is a superuser <em>and</em> owns the
 * tables, so RLS never applies to it. All assertions therefore run over R2DBC as a dedicated
 * non-superuser, non-owner role ({@code app_user}) without BYPASSRLS — the same shape a
 * production application role has.</p>
 *
 * <p>Requires Docker — skipped automatically in environments without Docker.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("RLS via app.current_tenant session variable")
class RlsSessionVariableIntegrationTest {

    private static final String APP_USER = "app_user";
    private static final String APP_PASSWORD = "app_pw";

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("osc_rls_test")
                    .withUsername("osc")
                    .withPassword("test");

    private static ConnectionFactory appUserConnections;

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final UUID OBJECT_A = UUID.randomUUID();
    private static final UUID OBJECT_B = UUID.randomUUID();
    private static final UUID RECORD_A = UUID.randomUUID();
    private static final UUID RECORD_B = UUID.randomUUID();

    @BeforeAll
    static void migrateSeedAndCreateAppRole() throws SQLException {
        // Real production migrations: schema + RLS policies (V1) + seed (V2+).
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();

        try (var admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var stmt = admin.createStatement()) {

            // Non-superuser, non-owner application role: RLS applies to it.
            stmt.execute("CREATE ROLE " + APP_USER + " LOGIN PASSWORD '" + APP_PASSWORD + "'");
            stmt.execute("GRANT USAGE ON SCHEMA public TO " + APP_USER);
            stmt.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + APP_USER);

            // Seed two tenants with one record each (bootstrap user owns the tables → bypasses RLS).
            insertTenant(admin, TENANT_A, "Tenant A");
            insertTenant(admin, TENANT_B, "Tenant B");
            insertObject(admin, OBJECT_A, TENANT_A);
            insertObject(admin, OBJECT_B, TENANT_B);
            insertRecord(admin, RECORD_A, TENANT_A, OBJECT_A, "A's secret record");
            insertRecord(admin, RECORD_B, TENANT_B, OBJECT_B, "B's record");
        }

        appUserConnections = ConnectionFactories.get(
                ConnectionFactoryOptions.builder()
                        .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                        .option(ConnectionFactoryOptions.HOST, POSTGRES.getHost())
                        .option(ConnectionFactoryOptions.PORT, POSTGRES.getMappedPort(5432))
                        .option(ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
                        .option(ConnectionFactoryOptions.USER, APP_USER)
                        .option(ConnectionFactoryOptions.PASSWORD, APP_PASSWORD)
                        .build());
    }

    @Test
    @DisplayName("guard: app_user has neither SUPERUSER nor BYPASSRLS (RLS actually applies)")
    void appUserCannotBypassRls() throws SQLException {
        try (var admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = admin.prepareStatement(
                     "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = ?")) {
            ps.setString(1, APP_USER);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("app_user role exists").isTrue();
                assertThat(rs.getBoolean("rolsuper")).as("rolsuper").isFalse();
                assertThat(rs.getBoolean("rolbypassrls")).as("rolbypassrls").isFalse();
            }
        }
    }

    @Test
    @DisplayName("tenant B session cannot see tenant A's record by id — no tenant_id filter in the query")
    void crossTenantQueryById_blockedByRls() {
        List<UUID> visible = queryIds(TENANT_B.toString(),
                "SELECT id FROM record WHERE id = $1", RECORD_A);

        assertThat(visible).isEmpty();
    }

    @Test
    @DisplayName("tenant A session sees its own record by id (positive control: grants + policy allow the owner tenant)")
    void ownTenantQueryById_allowedByRls() {
        List<UUID> visible = queryIds(TENANT_A.toString(),
                "SELECT id FROM record WHERE id = $1", RECORD_A);

        assertThat(visible).containsExactly(RECORD_A);
    }

    @Test
    @DisplayName("unfiltered SELECT over record returns only the active tenant's rows")
    void unfilteredSelect_gatedBySessionVariable() {
        List<UUID> visible = queryIds(TENANT_B.toString(), "SELECT id FROM record");

        assertThat(visible).containsExactly(RECORD_B);
    }

    @Test
    @DisplayName("no app.current_tenant set → zero rows visible (fail closed)")
    void missingSessionVariable_failsClosed() {
        List<UUID> visible = queryIds(null, "SELECT id FROM record");

        assertThat(visible).isEmpty();
    }

    @Test
    @DisplayName("metadata tables are gated too: tenant B session cannot see tenant A's md_object")
    void metadataTables_alsoGated() {
        List<UUID> visible = queryIds(TENANT_B.toString(), "SELECT id FROM md_object");

        assertThat(visible).containsExactly(OBJECT_B); // neither tenant A's nor the system tenant's objects
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Runs an optional tenant activation followed by a query on the SAME physical connection
     * (set_config is session-scoped, so both statements must share the session), as app_user.
     * Activation uses the identical SQL the production repositories use.
     */
    private static List<UUID> queryIds(String tenantIdOrNull, String sql, Object... binds) {
        return Flux.usingWhen(
                        appUserConnections.create(),
                        conn -> activateTenant(conn, tenantIdOrNull).thenMany(selectIds(conn, sql, binds)),
                        Connection::close)
                .collectList()
                .block();
    }

    private static Mono<Void> activateTenant(Connection conn, String tenantIdOrNull) {
        if (tenantIdOrNull == null) {
            return Mono.empty();
        }
        return Flux.from(conn.createStatement("SELECT set_config('app.current_tenant', $1, false)")
                        .bind("$1", tenantIdOrNull)
                        .execute())
                .flatMap(result -> result.map((row, meta) -> 1))
                .then();
    }

    private static Flux<UUID> selectIds(Connection conn, String sql, Object... binds) {
        Statement statement = conn.createStatement(sql);
        for (int i = 0; i < binds.length; i++) {
            statement.bind("$" + (i + 1), binds[i]);
        }
        return Flux.from(statement.execute())
                .flatMap(result -> result.map((row, meta) -> row.get("id", UUID.class)));
    }

    private static void insertTenant(java.sql.Connection admin, UUID id, String name) throws SQLException {
        try (PreparedStatement ps = admin.prepareStatement(
                "INSERT INTO tenant (id, api_name, display_name) VALUES (?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, name.toLowerCase().replace(' ', '-') + "-" + id);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    private static void insertObject(java.sql.Connection admin, UUID id, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = admin.prepareStatement(
                "INSERT INTO md_object (id, tenant_id, api_name, label, label_plural) "
                        + "VALUES (?, ?, 'Account', 'Account', 'Accounts')")) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.executeUpdate();
        }
    }

    private static void insertRecord(java.sql.Connection admin, UUID id, UUID tenantId,
                                     UUID objectId, String name) throws SQLException {
        try (PreparedStatement ps = admin.prepareStatement(
                "INSERT INTO record (id, tenant_id, object_id, name) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setObject(3, objectId);
            ps.setString(4, name);
            ps.executeUpdate();
        }
    }
}
