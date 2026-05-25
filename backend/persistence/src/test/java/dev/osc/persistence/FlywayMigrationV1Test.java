package dev.osc.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that V1 and V2 Flyway migrations apply cleanly against a real PostgreSQL instance.
 * Uses Testcontainers — requires Docker to run.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlywayMigrationV1Test {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("osc_test")
                    .withUsername("osc")
                    .withPassword("test");

    private static Connection conn;

    private static final List<String> EXPECTED_TABLES = List.of(
            "tenant", "md_object", "md_field", "md_validation_rule",
            "md_layout", "md_list_view", "md_automation", "record", "outbox_event"
    );

    @BeforeAll
    static void runMigrations() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @AfterAll
    static void closeConnection() throws SQLException {
        if (conn != null) conn.close();
    }

    // ── Schema ───────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("all expected tables are created")
    void allTablesExist() throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        for (String table : EXPECTED_TABLES) {
            try (ResultSet rs = meta.getTables(null, "public", table, new String[]{"TABLE"})) {
                assertTrue(rs.next(), "Expected table to exist: " + table);
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("record table has GIN index on data column")
    void recordTable_hasGinIndex() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM pg_indexes WHERE tablename = 'record' AND indexname = 'idx_record_data_gin'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "GIN index idx_record_data_gin must exist on record.data");
        }
    }

    @Test
    @Order(3)
    @DisplayName("record table has composite index on (tenant_id, object_id)")
    void recordTable_hasCompositeIndex() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM pg_indexes WHERE tablename = 'record' AND indexname = 'idx_record_tenant_object'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "Composite index idx_record_tenant_object must exist");
        }
    }

    @Test
    @Order(4)
    @DisplayName("RLS is enabled on all data tables")
    void rlsEnabledOnAllDataTables() throws SQLException {
        List<String> rlsTables = List.of(
                "md_object", "md_field", "md_validation_rule",
                "md_layout", "md_list_view", "md_automation", "record", "outbox_event");
        for (String table : rlsTables) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT relrowsecurity FROM pg_class WHERE relname = ?")) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Table " + table + " must exist in pg_class");
                    assertTrue(rs.getBoolean(1), "RLS must be enabled on " + table);
                }
            }
        }
    }

    @Test
    @Order(5)
    @DisplayName("outbox_event has partial index on PENDING status")
    void outboxEvent_hasPendingPartialIndex() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM pg_indexes WHERE tablename = 'outbox_event' AND indexname = 'idx_outbox_pending'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "Partial index idx_outbox_pending must exist on outbox_event");
        }
    }

    // ── Seed Data (V2) ────────────────────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("V2 seed: Account and Contact objects exist for system tenant")
    void seedData_accountAndContactPresent() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT api_name FROM md_object WHERE tenant_id = '00000000-0000-0000-0000-000000000001' ORDER BY api_name");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("Account", rs.getString(1));
            assertTrue(rs.next());
            assertEquals("Contact", rs.getString(1));
        }
    }

    @Test
    @Order(7)
    @DisplayName("V2 seed: Account has required 'name' field stored as COLUMN")
    void seedData_accountNameFieldIsColumn() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT storage_kind, is_required FROM md_field f "
                + "JOIN md_object o ON o.id = f.object_id "
                + "WHERE o.api_name = 'Account' AND f.api_name = 'name'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "Account.name field must exist");
            assertEquals("COLUMN", rs.getString("storage_kind"));
            assertTrue(rs.getBoolean("is_required"));
        }
    }

    @Test
    @Order(8)
    @DisplayName("V2 seed: migrations are idempotent (re-run does not fail)")
    void seedData_migrationsAreIdempotent() {
        assertDoesNotThrow(() ->
                Flyway.configure()
                        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                        .load()
                        .migrate()
        );
    }
}
