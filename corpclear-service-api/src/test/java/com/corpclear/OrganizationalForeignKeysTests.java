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
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class OrganizationalForeignKeysTests {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    @Autowired DataSource dataSource;

    @Test
    void rejectsOrphansImmediatelyForEachRelationship() throws Exception {
        for (String[] link : new String[][]{{"users", "department_id"}, {"users", "manager_id"}, {"departments", "manager_id"}}) {
            try (var c = dataSource.getConnection(); var st = c.createStatement()) {
                c.setAutoCommit(false);
                String table = link[0], column = link[1];
                String insert = table.equals("users")
                        ? "INSERT INTO users VALUES (gen_random_uuid(), 'Test', gen_random_uuid()::text, 'hash', 'MANAGER', 'ACTIVE', " + (column.equals("department_id") ? "gen_random_uuid(), NULL" : "NULL, gen_random_uuid()") + ", now(), now())"
                        : "INSERT INTO departments VALUES (gen_random_uuid(), 'Invalid', gen_random_uuid(), now(), now())";
                assertThatThrownBy(() -> st.executeUpdate(insert)).isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getSQLState()).isEqualTo("23503"));
                c.rollback();
                if (table.equals("users")) st.executeUpdate("INSERT INTO users VALUES (gen_random_uuid(), 'Test', gen_random_uuid()::text, 'hash', 'MANAGER', 'ACTIVE', NULL, NULL, now(), now())");
                else st.executeUpdate("INSERT INTO departments VALUES (gen_random_uuid(), 'Valid', NULL, now(), now())");
                assertThatThrownBy(() -> st.executeUpdate("UPDATE " + table + " SET " + column + " = gen_random_uuid()"))
                        .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23503"));
                c.rollback();
            }
        }
    }
    @Test
    void bootstrapAndRestrictiveDeletesPreserveIndependentLinks() throws Exception {
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            c.setAutoCommit(false);
            st.executeUpdate("INSERT INTO departments VALUES ('11111111-1111-1111-1111-111111111111', 'First', NULL, now(), now()), ('22222222-2222-2222-2222-222222222222', 'Second', NULL, now(), now())");
            st.executeUpdate("INSERT INTO users VALUES ('33333333-3333-3333-3333-333333333333', 'Department manager', 'dept@test', 'hash', 'MANAGER', 'ACTIVE', '11111111-1111-1111-1111-111111111111', NULL, now(), now()), ('44444444-4444-4444-4444-444444444444', 'Direct manager', 'direct@test', 'hash', 'MANAGER', 'ACTIVE', '22222222-2222-2222-2222-222222222222', NULL, now(), now())");
            st.executeUpdate("UPDATE departments SET manager_id = '33333333-3333-3333-3333-333333333333' WHERE name = 'First'");
            st.executeUpdate("INSERT INTO users VALUES (gen_random_uuid(), 'Employee', 'employee@test', 'hash', 'EMPLOYEE', 'ACTIVE', '11111111-1111-1111-1111-111111111111', '44444444-4444-4444-4444-444444444444', now(), now())");
            for (String target : new String[]{"departments WHERE name = 'First'", "users WHERE email = 'direct@test'", "users WHERE email = 'dept@test'"}) {
                var savepoint = c.setSavepoint();
                assertThatThrownBy(() -> st.executeUpdate("DELETE FROM " + target)).isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getSQLState()).isEqualTo("23503"));
                c.rollback(savepoint);
            }
            st.executeUpdate("UPDATE users SET department_id = '22222222-2222-2222-2222-222222222222' WHERE email = 'employee@test'");
            st.executeUpdate("UPDATE users SET status = 'DISABLED', role = 'AUDITOR' WHERE role = 'MANAGER'");
            try (var rows = st.executeQuery("SELECT manager_id FROM users WHERE email = 'employee@test'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("44444444-4444-4444-4444-444444444444");
            }
            st.executeUpdate("UPDATE users SET manager_id = '44444444-4444-4444-4444-444444444444' WHERE email = 'dept@test'");
            st.executeUpdate("UPDATE users SET manager_id = '33333333-3333-3333-3333-333333333333' WHERE email = 'direct@test'");
            c.rollback();
        }
    }

    @Test
    void legacyOrphanRequiresRepairAndRollbackPreservesRows() throws Exception {
        try (var isolated = new PostgreSQLContainer("postgres:17-alpine")) {
            isolated.start();
            var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(isolated.getJdbcUrl(), isolated.getUsername(), isolated.getPassword());
            migrate(source, "db/changelog/changes/001-create-departments.xml", 0);
            try (var c = source.getConnection(); var st = c.createStatement()) {
                st.executeUpdate("INSERT INTO departments VALUES (gen_random_uuid(), 'Legacy', '33333333-3333-3333-3333-333333333333', now(), now())");
            }
            assertThatThrownBy(() -> migrate(source, "db/changelog/db.changelog-master.xml", 0)).isInstanceOf(liquibase.exception.LiquibaseException.class);
            try (var c = source.getConnection(); var st = c.createStatement()) {
                try (var rows = st.executeQuery("SELECT manager_id FROM departments WHERE name = 'Legacy'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("33333333-3333-3333-3333-333333333333");
                }
                st.executeUpdate("INSERT INTO users VALUES ('33333333-3333-3333-3333-333333333333', 'Restored', 'restored@test', 'hash', 'MANAGER', 'ACTIVE', NULL, NULL, now(), now())");
            }
            migrate(source, "db/changelog/db.changelog-master.xml", 0);
            migrate(source, "db/changelog/db.changelog-master.xml", 1);
            try (var c = source.getConnection(); var st = c.createStatement(); var rows = st.executeQuery("SELECT (SELECT count(*) FROM users), (SELECT count(*) FROM departments), (SELECT count(*) FROM pg_constraint WHERE contype = 'f' AND connamespace = 'public'::regnamespace)")) {
                rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); assertThat(rows.getInt(2)).isEqualTo(1); assertThat(rows.getInt(3)).isZero();
            }
            migrate(source, "db/changelog/db.changelog-master.xml", 1);
            try (var c = source.getConnection(); var st = c.createStatement()) {
                st.executeUpdate("UPDATE departments SET manager_id = NULL");
            }
            migrate(source, "db/changelog/db.changelog-master.xml", 0);
            try (var c = source.getConnection(); var st = c.createStatement(); var rows = st.executeQuery("SELECT (SELECT count(*) FROM departments), (SELECT count(*) FROM users), (SELECT count(*) FROM databasechangelog)")) {
                rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); assertThat(rows.getInt(2)).isZero(); assertThat(rows.getInt(3)).isEqualTo(3);
            }
            try (var c = source.getConnection(); var st = c.createStatement(); var rows = st.executeQuery("SELECT conname, convalidated, condeferrable, condeferred, confdeltype, confupdtype FROM pg_constraint WHERE contype = 'f' AND connamespace = 'public'::regnamespace ORDER BY conname")) {
                for (String name : new String[]{"fk_departments_manager", "fk_users_department", "fk_users_manager"}) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo(name);
                    assertThat(rows.getBoolean(2)).isTrue();
                    assertThat(rows.getBoolean(3)).isFalse();
                    assertThat(rows.getBoolean(4)).isFalse();
                    assertThat(rows.getString(5)).isEqualTo("r");
                    assertThat(rows.getString(6)).isEqualTo("r");
                }
                assertThat(rows.next()).isFalse();
            }
        }
    }

    private static void migrate(DataSource source, String path, int rollback) throws Exception {
        try (var c = source.getConnection(); var lb = new liquibase.Liquibase(path, new liquibase.resource.ClassLoaderResourceAccessor(), new liquibase.database.jvm.JdbcConnection(c))) {
            if (rollback == 0) lb.update(new liquibase.Contexts(), new liquibase.LabelExpression());
            else lb.rollback(rollback, new liquibase.Contexts(), new liquibase.LabelExpression());
            liquibase.Scope.getCurrentScope().getSingleton(liquibase.changelog.FastCheckService.class).clearCache();
        }
    }
}
