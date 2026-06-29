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
 * TDD — written before V5 migration exists.
 * Verifies the extended metadata model (ADR-006: md_relationship, md_record_type,
 * md_layout_assignment, record.record_type_id) and the Kotlin Scripting tables
 * (ADR-005: md_script, script_execution_log), their RLS policies, and their
 * integrity constraints.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlywayMigrationV5Test {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("osc_test")
                    .withUsername("osc")
                    .withPassword("test");

    private static Connection conn;

    private static final List<String> NEW_TABLES = List.of(
            "md_record_type", "md_relationship", "md_layout_assignment",
            "md_script", "script_execution_log"
    );

    private static UUID tenantId;

    @BeforeAll
    static void runMigrations() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        tenantId = insertTenant();
    }

    @AfterAll
    static void closeConnection() throws SQLException {
        if (conn != null) conn.close();
    }

    private static UUID insertTenant() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tenant(api_name, display_name) VALUES (?, ?) RETURNING id")) {
            ps.setString(1, "v5-test-tenant");
            ps.setString(2, "V5 Test Tenant");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("all V5 tables are created")
    void newTablesExist() throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        for (String table : NEW_TABLES) {
            try (ResultSet rs = meta.getTables(null, "public", table, new String[]{"TABLE"})) {
                assertTrue(rs.next(), "Expected table to exist: " + table);
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("record gained a nullable record_type_id column")
    void record_hasRecordTypeIdColumn() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'record' AND column_name = 'record_type_id'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "record.record_type_id must exist");
            assertEquals("YES", rs.getString(1), "record_type_id must be nullable");
        }
    }

    @Test
    @Order(3)
    @DisplayName("all V5 tables have RLS enabled")
    void allNewTables_haveRls() throws SQLException {
        for (String table : NEW_TABLES) {
            assertRlsEnabled(table);
        }
    }

    @Test
    @Order(4)
    @DisplayName("md_relationship rejects an invalid relationship_type")
    void relationship_rejectsInvalidType() throws SQLException {
        conn.setAutoCommit(false);
        try {
            UUID objectId = insertObject("V5RelObject1");
            assertThrows(SQLException.class, () -> insertRelationship(
                    "NOT_A_TYPE", objectId, objectId, null, null, "RESTRICT"));
        } finally {
            conn.rollback();
            conn.setAutoCommit(true);
        }
    }

    @Test
    @Order(5)
    @DisplayName("MASTER_DETAIL relationship must have on_delete = CASCADE")
    void relationship_masterDetail_requiresCascade() throws SQLException {
        conn.setAutoCommit(false);
        try {
            UUID childId = insertObject("V5MdChild");
            UUID parentId = insertObject("V5MdParent");
            UUID fieldId = insertField(childId, "parent_lookup");

            assertThrows(SQLException.class, () -> insertRelationship(
                    "MASTER_DETAIL", childId, parentId, fieldId, null, "RESTRICT"));

            assertDoesNotThrow(() -> insertRelationship(
                    "MASTER_DETAIL", childId, parentId, fieldId, null, "CASCADE"));
        } finally {
            conn.rollback();
            conn.setAutoCommit(true);
        }
    }

    @Test
    @Order(6)
    @DisplayName("MANY_TO_MANY relationship requires junction_object_id and forbids field_id")
    void relationship_manyToMany_requiresJunctionObject() throws SQLException {
        conn.setAutoCommit(false);
        try {
            UUID objA = insertObject("V5M2mA");
            UUID objB = insertObject("V5M2mB");
            UUID junction = insertObject("V5M2mJunction");
            UUID fieldId = insertField(objA, "some_field");

            assertThrows(SQLException.class, () -> insertRelationship(
                    "MANY_TO_MANY", objA, objB, null, null, "RESTRICT"));
            assertThrows(SQLException.class, () -> insertRelationship(
                    "MANY_TO_MANY", objA, objB, fieldId, junction, "RESTRICT"));
            assertDoesNotThrow(() -> insertRelationship(
                    "MANY_TO_MANY", objA, objB, null, junction, "RESTRICT"));
        } finally {
            conn.rollback();
            conn.setAutoCommit(true);
        }
    }

    @Test
    @Order(7)
    @DisplayName("md_script cannot be activated while compile_errors is non-empty (NNG-023)")
    void script_cannotActivateWithCompileErrors() throws SQLException {
        conn.setAutoCommit(false);
        try {
            UUID objectId = insertObject("V5ScriptObject");

            assertThrows(SQLException.class, () -> insertScript(
                    objectId, "TRIGGER", "BEFORE_INSERT", true, "['boom']"));

            assertDoesNotThrow(() -> insertScript(
                    objectId, "TRIGGER", "BEFORE_INSERT", true, "[]"));
        } finally {
            conn.rollback();
            conn.setAutoCommit(true);
        }
    }

    @Test
    @Order(8)
    @DisplayName("md_script enforces kind-specific required fields")
    void script_enforcesKindSpecificFields() throws SQLException {
        conn.setAutoCommit(false);
        try {
            UUID objectId = insertObject("V5ScriptKindObject");

            // TRIGGER without trigger_event must fail
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO md_script(tenant_id, object_id, kind, source) VALUES (?, ?, 'TRIGGER', 'fun x(){}')")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, objectId);
                    ps.executeUpdate();
                }
            });

            // SCHEDULED without schedule_cron must fail
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO md_script(tenant_id, object_id, kind, source) VALUES (?, ?, 'SCHEDULED', 'fun x(){}')")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, objectId);
                    ps.executeUpdate();
                }
            });
        } finally {
            conn.rollback();
            conn.setAutoCommit(true);
        }
    }

    @Test
    @Order(9)
    @DisplayName("script_execution_log references md_script and cascades on delete")
    void scriptExecutionLog_cascadesWithScript() throws SQLException {
        conn.setAutoCommit(false);
        try {
            UUID objectId = insertObject("V5LogObject");
            UUID scriptId = insertScript(objectId, "BATCH", null, false, "[]");

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO script_execution_log(tenant_id, script_id, duration_ms, outcome) "
                    + "VALUES (?, ?, ?, 'SUCCESS')")) {
                ps.setObject(1, tenantId);
                ps.setObject(2, scriptId);
                ps.setInt(3, 42);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM md_script WHERE id = ?")) {
                ps.setObject(1, scriptId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM script_execution_log WHERE script_id = ?")) {
                ps.setObject(1, scriptId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(0, rs.getInt(1), "log rows must cascade-delete with their script");
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

    private UUID insertObject(String apiName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO md_object(tenant_id, api_name, label, label_plural) "
                + "VALUES (?, ?, ?, ?) RETURNING id")) {
            ps.setObject(1, tenantId);
            ps.setString(2, apiName);
            ps.setString(3, apiName);
            ps.setString(4, apiName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID insertField(UUID objectId, String apiName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO md_field(tenant_id, object_id, api_name, label, field_type) "
                + "VALUES (?, ?, ?, ?, 'LOOKUP') RETURNING id")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, objectId);
            ps.setString(3, apiName);
            ps.setString(4, apiName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void insertRelationship(String type, UUID childId, UUID parentId, UUID fieldId,
                                     UUID junctionId, String onDelete) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO md_relationship"
                + "(tenant_id, relationship_type, child_object_id, parent_object_id, field_id, "
                + "junction_object_id, on_delete) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, tenantId);
            ps.setString(2, type);
            ps.setObject(3, childId);
            ps.setObject(4, parentId);
            ps.setObject(5, fieldId);
            ps.setObject(6, junctionId);
            ps.setString(7, onDelete);
            ps.executeUpdate();
        }
    }

    private UUID insertScript(UUID objectId, String kind, String triggerEvent,
                               boolean isActive, String compileErrorsJson) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO md_script(tenant_id, object_id, kind, trigger_event, source, "
                + "is_active, compile_errors) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, objectId);
            ps.setString(3, kind);
            ps.setString(4, triggerEvent);
            ps.setString(5, "fun execute(ctx: ExecutionContext) {}");
            ps.setBoolean(6, isActive);
            ps.setString(7, compileErrorsJson);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }
}
