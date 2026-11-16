package com.corpclear;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class UsersMigrationTests {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    DataSource dataSource;

    @org.junit.jupiter.api.BeforeEach
    void clearUsers() throws SQLException {
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            st.executeUpdate("UPDATE departments SET manager_id = NULL");
            st.executeUpdate("DELETE FROM users");
            st.executeUpdate("DELETE FROM departments");
        }
    }

    @Test
    void createsUsersWithExplicitIdentityAndDates() throws Exception {
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            st.executeUpdate("INSERT INTO users (id, name, email, password_hash, role, status, created_at, updated_at) VALUES ('11111111-1111-1111-1111-111111111111', 'Ána  Silva', 'Ana@Empresa.com', 'opaque', 'MANAGER', 'ACTIVE', '2026-11-06T10:00:00-03:00', '2026-11-06T11:00:00-03:00')");
            try (var rows = st.executeQuery("SELECT * FROM users")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("name")).isEqualTo("Ána  Silva");
                assertThat(rows.getString("email")).isEqualTo("Ana@Empresa.com");
                assertThat(rows.getObject("department_id")).isNull();
                assertThat(rows.getObject("manager_id")).isNull();
                assertThat(rows.getObject("id")).isEqualTo(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"));
                assertThat(rows.getObject("created_at", java.time.OffsetDateTime.class).toInstant()).isEqualTo(java.time.Instant.parse("2026-11-06T13:00:00Z"));
                assertThat(rows.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()).isEqualTo(java.time.Instant.parse("2026-11-06T14:00:00Z"));
            }
        }
    }

    private static String insert(String column, String value) {
        return insert(column == null ? java.util.Map.of() : java.util.Collections.singletonMap(column, value));
    }

    private static String insert(java.util.Map<String, String> overrides) {
        var values = new java.util.LinkedHashMap<String, String>();
        values.put("id", "gen_random_uuid()");
        values.put("name", "'Ána  Silva'");
        values.put("email", "gen_random_uuid()::text || '@empresa.com'");
        values.put("password_hash", "'opaque'");
        values.put("role", "'MANAGER'");
        values.put("status", "'ACTIVE'");
        values.put("department_id", "NULL");
        values.put("manager_id", "NULL");
        values.put("created_at", "'2026-11-06T13:00:00Z'");
        values.put("updated_at", "'2026-11-06T14:00:00Z'");
        overrides.forEach((column, value) -> {
            if (value == null) values.remove(column);
            else values.put(column, value);
        });
        return "INSERT INTO users (" + String.join(",", values.keySet()) + ") VALUES (" + String.join(",", values.values()) + ")";
    }

    private static void reject(java.sql.Statement st, String sql, String state) {
        assertThatThrownBy(() -> st.executeUpdate(sql)).isInstanceOfSatisfying(SQLException.class,
                e -> assertThat(e.getSQLState()).isEqualTo(state));
    }

    @Test
    void enforcesRequiredValuesLengthsAndIdentity() throws Exception {
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            for (String column : new String[]{"id", "name", "email", "password_hash", "role", "status", "created_at", "updated_at"}) {
                reject(st, insert(column, "NULL"), "23502");
                reject(st, insert(column, null), "23502");
            }
            for (String column : new String[]{"name", "email", "password_hash"}) {
                int limit = column.equals("name") ? 150 : 255;
                st.executeUpdate(insert(column, "'" + "x".repeat(limit) + "'"));
                reject(st, insert(column, "'" + "x".repeat(limit + 1) + "'"), "22001");
            }
            for (String column : new String[]{"role", "status"}) {
                reject(st, insert(column, "'" + "x".repeat(31) + "'"), "22001");
            }
            String id = "'22222222-2222-2222-2222-222222222222'";
            st.executeUpdate(insert("id", id));
            reject(st, insert("id", id), "23505");
        }
    }

    @Test
    void enforcesStorageRulesAndCaseInsensitiveEmailWithoutProviderNormalization() throws Exception {
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            for (String column : new String[]{"name", "email", "password_hash"}) {
                for (String invalid : new String[]{"", "   "}) reject(st, insert(column, "'" + invalid + "'"), "23514");
            }
            for (String column : new String[]{"name", "email"}) {
                for (String invalid : new String[]{" Leading", "Trailing "}) reject(st, insert(column, "'" + invalid + "'"), "23514");
                st.executeUpdate(insert(column, "E'\\tTab\\t'"));
            }
            st.executeUpdate(insert("password_hash", "' arbitrary hash '"));
            for (String email : new String[]{"Ana@Empresa.com", "a.na@empresa.com", "ana+tag@empresa.com"}) {
                st.executeUpdate(insert("email", "'" + email + "'"));
            }
            reject(st, insert("email", "'Ana@Empresa.com'"), "23505");
            reject(st, insert("email", "'ana@empresa.com'"), "23505");
            reject(st, "UPDATE users SET email = 'ANA@EMPRESA.COM' WHERE email = 'a.na@empresa.com'", "23505");
            reject(st, "UPDATE users SET name = ' Leading'", "23514");
            reject(st, "UPDATE users SET password_hash = '  '", "23514");
        }
    }

    @Test
    void enforcesRolesStatusAndLinksOnInsertAndUpdate() throws Exception {
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            st.executeUpdate("INSERT INTO departments VALUES ('44444444-4444-4444-4444-444444444444', 'Links', NULL, now(), now())");
            st.executeUpdate(insert("id", "'55555555-5555-5555-5555-555555555555'"));
            for (String role : new String[]{"MANAGER", "FINANCE", "AUDITOR", "ADMIN"}) {
                st.executeUpdate(insert("role", "'" + role + "'"));
                st.executeUpdate(insert(java.util.Map.of("role", "'" + role + "'", "department_id", "'44444444-4444-4444-4444-444444444444'")));
                st.executeUpdate(insert(java.util.Map.of("role", "'" + role + "'", "manager_id", "'55555555-5555-5555-5555-555555555555'")));
            }
            for (String status : new String[]{"ACTIVE", "BLOCKED", "DISABLED"}) {
                st.executeUpdate(insert("status", "'" + status + "'"));
                var employee = new java.util.HashMap<String, String>();
                employee.put("role", "'EMPLOYEE'");
                employee.put("status", "'" + status + "'");
                reject(st, insert(employee), "23514");
                employee.put("department_id", "'44444444-4444-4444-4444-444444444444'");
                reject(st, insert(employee), "23514");
                employee.remove("department_id");
                employee.put("manager_id", "'55555555-5555-5555-5555-555555555555'");
                reject(st, insert(employee), "23514");
                employee.put("department_id", "'44444444-4444-4444-4444-444444444444'");
                // References exist; local EMPLOYEE rules apply in every status.
                st.executeUpdate(insert(employee));
                for (String column : new String[]{"department_id", "manager_id"}) {
                    reject(st, "UPDATE users SET " + column + " = NULL WHERE role = 'EMPLOYEE' AND status = '" + status + "'", "23514");
                }
            }
            for (String role : new String[]{"SYSTEM", "UNKNOWN", "manager", ""}) reject(st, insert("role", "'" + role + "'"), "23514");
            for (String status : new String[]{"UNKNOWN", "active", ""}) reject(st, insert("status", "'" + status + "'"), "23514");
            reject(st, "UPDATE users SET role = 'EMPLOYEE' WHERE department_id IS NULL", "23514");
            reject(st, "UPDATE users SET department_id = NULL WHERE role = 'EMPLOYEE'", "23514");
            reject(st, "UPDATE users SET manager_id = NULL WHERE role = 'EMPLOYEE'", "23514");
            for (String role : new String[]{"EMPLOYEE", "MANAGER", "FINANCE", "AUDITOR", "ADMIN"}) {
                String self = insert(java.util.Map.of(
                        "id", "'33333333-3333-3333-3333-333333333333'",
                        "role", "'" + role + "'",
                        "department_id", "'44444444-4444-4444-4444-444444444444'",
                        "manager_id", "'33333333-3333-3333-3333-333333333333'"));
                reject(st, self, "23514");
                reject(st, "UPDATE users SET manager_id = id WHERE role = '" + role + "'", "23514");
            }
            reject(st, "UPDATE users SET role = 'SYSTEM'", "23514");
            reject(st, "UPDATE users SET status = 'UNKNOWN'", "23514");
        }
    }

    @Test
    void schemaHasRequiredTypesLengthsAndNoDefaultsOrCustomTriggers() throws Exception {
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            try (var rows = st.executeQuery("SELECT column_name, data_type, is_nullable, column_default, character_maximum_length FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'users' ORDER BY ordinal_position")) {
                for (String[] expected : new String[][]{
                        {"id", "uuid", "NO", "0"}, {"name", "character varying", "NO", "150"},
                        {"email", "character varying", "NO", "255"}, {"password_hash", "character varying", "NO", "255"},
                        {"role", "character varying", "NO", "30"}, {"status", "character varying", "NO", "30"},
                        {"department_id", "uuid", "YES", "0"}, {"manager_id", "uuid", "YES", "0"},
                        {"created_at", "timestamp with time zone", "NO", "0"}, {"updated_at", "timestamp with time zone", "NO", "0"}}) {
                    assertThat(rows.next()).isTrue();
                    for (int i = 0; i < 3; i++) assertThat(rows.getString(i + 1)).isEqualTo(expected[i]);
                    assertThat(rows.getString(4)).isNull();
                    assertThat(rows.getInt(5)).isEqualTo(Integer.parseInt(expected[3]));
                }
                assertThat(rows.next()).isFalse();
            }
            try (var rows = st.executeQuery("SELECT (SELECT count(*) FROM pg_trigger WHERE tgrelid = 'users'::regclass AND NOT tgisinternal), (SELECT count(*) FROM pg_constraint WHERE conrelid = 'users'::regclass AND contype = 'u')")) {
                rows.next();
                assertThat(rows.getInt(1)).isZero();
                assertThat(rows.getInt(2)).isZero();
            }
        }
    }

    @Test
    void upgradesDepartmentsPreservesDataAndReappliesAfterUsersRollback() throws Exception {
        try (var isolated = new PostgreSQLContainer("postgres:17-alpine")) {
            isolated.start();
            var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(isolated.getJdbcUrl(), isolated.getUsername(), isolated.getPassword());
            try (var c = source.getConnection(); var lb = new liquibase.Liquibase("db/changelog/changes/001-create-departments.xml", new liquibase.resource.ClassLoaderResourceAccessor(), new liquibase.database.jvm.JdbcConnection(c))) {
                lb.update(new liquibase.Contexts(), new liquibase.LabelExpression());
            }
            try (var c = source.getConnection(); var st = c.createStatement()) {
                st.executeUpdate("INSERT INTO departments VALUES ('44444444-4444-4444-4444-444444444444', 'Preserved', NULL, '2026-11-06T13:00:00Z', '2026-11-06T14:00:00Z')");
            }
            applyMigration(source);
            try (var c = source.getConnection(); var st = c.createStatement()) {
                st.executeUpdate(insert("email", "'Preserved@empresa.com'"));
                applyMigration(source);
                try (var rows = st.executeQuery("SELECT email FROM users")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("Preserved@empresa.com");
                    assertThat(rows.next()).isFalse();
                }
                try (var rows = st.executeQuery("SELECT id, count(*) FROM databasechangelog GROUP BY id ORDER BY id")) {
                    for (String id : new String[]{"001-create-departments", "002-create-users", "003-create-organizational-foreign-keys", "004-create-organizational-indexes"}) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(1)).isEqualTo(id);
                        assertThat(rows.getInt(2)).isEqualTo(1);
                    }
                    assertThat(rows.next()).isFalse();
                }
            }
            try (var c = source.getConnection(); var lb = new liquibase.Liquibase("db/changelog/db.changelog-master.xml", new liquibase.resource.ClassLoaderResourceAccessor(), new liquibase.database.jvm.JdbcConnection(c))) {
                lb.rollback(3, new liquibase.Contexts(), new liquibase.LabelExpression());
                liquibase.Scope.getCurrentScope().getSingleton(liquibase.changelog.FastCheckService.class).clearCache();
            }
            try (var c = source.getConnection(); var st = c.createStatement()) {
                try (var rows = st.executeQuery("SELECT to_regclass('users'), (SELECT count(*) FROM databasechangelog), (SELECT locked FROM databasechangeloglock WHERE id = 1), (SELECT count(*) FROM pg_class WHERE relname LIKE '%users%'), (SELECT count(*) FROM pg_constraint WHERE conname LIKE '%users%')")) {
                    rows.next();
                    assertThat(rows.getString(1)).isNull();
                    assertThat(rows.getInt(2)).isEqualTo(1);
                    assertThat(rows.getBoolean(3)).isFalse();
                    assertThat(rows.getInt(4)).isZero();
                    assertThat(rows.getInt(5)).isZero();
                }
                try (var rows = st.executeQuery("SELECT id, name, created_at, updated_at FROM departments")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject(1)).isEqualTo(java.util.UUID.fromString("44444444-4444-4444-4444-444444444444"));
                    assertThat(rows.getString(2)).isEqualTo("Preserved");
                    assertThat(rows.getObject(3, java.time.OffsetDateTime.class).toInstant()).isEqualTo(java.time.Instant.parse("2026-11-06T13:00:00Z"));
                    assertThat(rows.getObject(4, java.time.OffsetDateTime.class).toInstant()).isEqualTo(java.time.Instant.parse("2026-11-06T14:00:00Z"));
                    assertThat(rows.next()).isFalse();
                }
            }
            applyMigration(source);
            try (var c = source.getConnection(); var st = c.createStatement()) {
                st.executeUpdate(insert("email", "'Reapplied@empresa.com'"));
                reject(st, insert("email", "'reapplied@empresa.com'"), "23505");
                reject(st, insert("role", "'SYSTEM'"), "23514");
            }
        }
    }

    private static void applyMigration(DataSource source) throws Exception {
        var migration = new liquibase.integration.spring.SpringLiquibase();
        migration.setDataSource(source);
        migration.setResourceLoader(new org.springframework.core.io.DefaultResourceLoader());
        migration.setChangeLog("classpath:db/changelog/db.changelog-master.xml");
        migration.afterPropertiesSet();
    }
}
