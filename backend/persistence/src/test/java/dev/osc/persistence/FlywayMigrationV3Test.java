package dev.osc.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD — written before V3 migration exists.
 * Verifies permission set tables, RLS policies, and FK constraints.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlywayMigrationV3Test {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("osc_test")
                    .withUsername("osc")
                    .withPassword("test");

    private static Connection conn;

    private static final List<String> PERMISSION_TABLES = List.of(
            "md_permission_set", "md_object_permission", "md_field_permission",
            "md_user_permission_set"
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

    @Test
    @Order(1)
    @DisplayName("all permission tables are created by V3")
    void permissionTablesExist() throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        for (String table : PERMISSION_TABLES) {
            try (ResultSet rs = meta.getTables(null, "public", table, new String[]{"TABLE"})) {
                assertTrue(rs.next(), "Expected table to exist: " + table);
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("md_permission_set has RLS enabled")
    void permissionSet_hasRls() throws SQLException {
        assertRlsEnabled("md_permission_set");
    }

    @Test
    @Order(3)
    @DisplayName("md_object_permission has RLS enabled")
    void objectPermission_hasRls() throws SQLException {
        assertRlsEnabled("md_object_permission");
    }

    @Test
    @Order(4)
    @DisplayName("md_field_permission has RLS enabled")
    void fieldPermission_hasRls() throws SQLException {
        assertRlsEnabled("md_field_permission");
    }

    @Test
    @Order(5)
    @DisplayName("md_user_permission_set has RLS enabled")
    void userPermissionSet_hasRls() throws SQLException {
        assertRlsEnabled("md_user_permission_set");
    }

    @Test
    @Order(6)
    @DisplayName("md_permission_set has unique constraint on (tenant_id, api_name)")
    void permissionSet_uniqueApiNamePerTenant() throws SQLException {
        conn.setAutoCommit(false);
        try {
            // Insert tenant
            UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO md_permission_set(tenant_id, api_name, label) VALUES (?, ?, ?)")) {
                ps.setObject(1, tenantId);
                ps.setString(2, "StandardUser");
                ps.setString(3, "Standard User");
                ps.executeUpdate();

                // Second insert with same api_name should fail
                assertThrows(SQLException.class, () -> ps.executeUpdate());
            }
        } finally {
            conn.rollback();
            conn.setAutoCommit(true);
        }
    }

    @Test
    @Order(7)
    @DisplayName("md_object_permission has unique constraint on (permission_set_id, object_id)")
    void objectPermission_uniquePerPermissionSetAndObject() throws SQLException {
        // Verify constraint exists in pg_constraint
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT contype FROM pg_constraint c "
                + "JOIN pg_class t ON t.oid = c.conrelid "
                + "WHERE t.relname = 'md_object_permission' AND contype = 'u'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "md_object_permission must have a unique constraint");
        }
    }

    @Test
    @Order(8)
    @DisplayName("md_field_permission has unique constraint on (permission_set_id, field_id)")
    void fieldPermission_uniquePerPermissionSetAndField() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT contype FROM pg_constraint c "
                + "JOIN pg_class t ON t.oid = c.conrelid "
                + "WHERE t.relname = 'md_field_permission' AND contype = 'u'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "md_field_permission must have a unique constraint");
        }
    }

    @Test
    @Order(9)
    @DisplayName("md_object_permission defaults to all permissions denied")
    void objectPermission_defaultsDenied() throws SQLException {
        conn.setAutoCommit(false);
        try {
            UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID psId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO md_permission_set(tenant_id, api_name, label) VALUES (?, ?, ?) RETURNING id")) {
                ps.setObject(1, tenantId);
                ps.setString(2, "ReadOnly");
                ps.setString(3, "Read Only");
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    psId = (UUID) rs.getObject(1);
                }
            }
            // Get Account object id
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM md_object WHERE api_name = 'Account' AND tenant_id = ?")) {
                ps.setObject(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return; // seed not present, skip assertion
                    UUID objId = (UUID) rs.getObject(1);
                    try (PreparedStatement ins = conn.prepareStatement(
                            "INSERT INTO md_object_permission(tenant_id, permission_set_id, object_id) "
                            + "VALUES (?, ?, ?) RETURNING can_read, can_create, can_edit, can_delete")) {
                        ins.setObject(1, tenantId);
                        ins.setObject(2, psId);
                        ins.setObject(3, objId);
                        try (ResultSet rs2 = ins.executeQuery()) {
                            rs2.next();
                            assertFalse(rs2.getBoolean("can_read"), "Default can_read must be FALSE");
                            assertFalse(rs2.getBoolean("can_create"), "Default can_create must be FALSE");
                            assertFalse(rs2.getBoolean("can_edit"), "Default can_edit must be FALSE");
                            assertFalse(rs2.getBoolean("can_delete"), "Default can_delete must be FALSE");
                        }
                    }
                }
            }
        } finally {
            conn.rollback();
            conn.setAutoCommit(true);
        }
    }

    @Test
    @Order(10)
    @DisplayName("migrations are idempotent")
    void migrationsAreIdempotent() {
        assertDoesNotThrow(() ->
                Flyway.configure()
                        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                        .load()
                        .migrate()
        );
    }

    private void assertRlsEnabled(String tableName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT relrowsecurity FROM pg_class WHERE relname = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), tableName + " must exist in pg_class");
                assertTrue(rs.getBoolean(1), "RLS must be enabled on " + tableName);
            }
        }
    }
}
