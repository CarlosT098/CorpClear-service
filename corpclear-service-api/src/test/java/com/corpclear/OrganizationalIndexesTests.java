package com.corpclear;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class OrganizationalIndexesTests {
    private static final String MASTER = "db/changelog/db.changelog-master.xml";

    @Test
    void installsCompleteNonUniqueBtreeIndexesOnEmptyDatabase() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            migrate(source, MASTER, 0);
            assertIndexes(source);
        }
    }

    @Test
    void upgradeReexecutionRollbackAndReapplicationPreserveOrganizationalData() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            for (String file : new String[]{"001-create-departments", "002-create-users", "003-create-organizational-foreign-keys"}) {
                migrate(source, "db/changelog/changes/" + file + ".xml", 0);
            }
            try (var c = source.getConnection(); var st = c.createStatement()) {
                st.executeUpdate("INSERT INTO users VALUES ('33333333-3333-3333-3333-333333333333', 'Department manager', 'dept@test', 'hash', 'MANAGER', 'ACTIVE', NULL, NULL, now(), now()), ('44444444-4444-4444-4444-444444444444', 'Direct manager', 'direct@test', 'hash', 'MANAGER', 'ACTIVE', NULL, NULL, now(), now())");
                st.executeUpdate("INSERT INTO departments VALUES ('11111111-1111-1111-1111-111111111111', 'First', '33333333-3333-3333-3333-333333333333', now(), now()), (gen_random_uuid(), 'Second', '33333333-3333-3333-3333-333333333333', now(), now()), (gen_random_uuid(), 'Unassigned', NULL, now(), now())");
                for (String status : new String[]{"ACTIVE", "BLOCKED", "DISABLED"}) {
                    st.executeUpdate("INSERT INTO users VALUES (gen_random_uuid(), 'Employee', '" + status + "@test', 'hash', 'EMPLOYEE', '" + status + "', '11111111-1111-1111-1111-111111111111', '44444444-4444-4444-4444-444444444444', now(), now())");
                }
            }
            String before = preservedState(source);
            migrate(source, MASTER, 0);
            assertIndexes(source);
            assertThat(preservedState(source)).isEqualTo(before);
            String history = query(source, "SELECT jsonb_agg(to_jsonb(h) ORDER BY orderexecuted)::text FROM databasechangelog h");
            migrate(source, MASTER, 0);
            assertIndexes(source);
            assertThat(preservedState(source)).isEqualTo(before);
            assertThat(query(source, "SELECT jsonb_agg(to_jsonb(h) ORDER BY orderexecuted)::text FROM databasechangelog h")).isEqualTo(history);
            assertThat(query(source, "SELECT count(*)::text FROM databasechangelog")).isEqualTo("4");
            migrate(source, MASTER, 1);
            assertThat(query(source, "SELECT count(*)::text FROM pg_index WHERE indrelid IN ('users'::regclass, 'departments'::regclass) AND NOT indisunique")).isEqualTo("0");
            assertThat(query(source, "SELECT count(*)::text FROM databasechangelog")).isEqualTo("3");
            assertThat(preservedState(source)).isEqualTo(before);
            migrate(source, MASTER, 0);
            assertIndexes(source);
            assertThat(preservedState(source)).isEqualTo(before);
        }
    }

    private static String preservedState(DataSource source) throws Exception {
        return query(source, """
                SELECT jsonb_build_array(
                  (SELECT jsonb_agg(to_jsonb(u) ORDER BY id) FROM users u),
                  (SELECT jsonb_agg(to_jsonb(d) ORDER BY id) FROM departments d),
                  (SELECT jsonb_agg(to_jsonb(c) ORDER BY oid) FROM pg_constraint c WHERE connamespace = 'public'::regnamespace),
                  (SELECT jsonb_agg(to_jsonb(a) ORDER BY attrelid, attnum) FROM pg_attribute a WHERE attrelid IN ('users'::regclass, 'departments'::regclass)),
                  (SELECT jsonb_agg(indexdef ORDER BY indexname) FROM pg_indexes WHERE schemaname = 'public' AND indexname IN ('ux_departments_name_lower', 'ux_users_email_lower')),
                  (SELECT jsonb_agg(to_jsonb(h) ORDER BY orderexecuted) FROM databasechangelog h WHERE id <> '004-create-organizational-indexes')
                )::text
                """);
    }

    private static String query(DataSource source, String sql) throws Exception {
        try (var c = source.getConnection(); var st = c.createStatement(); var rows = st.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static void assertIndexes(DataSource source) throws Exception {
        try (var c = source.getConnection(); var st = c.createStatement(); var rows = st.executeQuery("""
                SELECT t.relname, a.attname, am.amname, i.indisunique, i.indisvalid,
                       i.indpred IS NULL, i.indexprs IS NULL, i.indnatts, i.indnkeyatts, x.relname
                FROM pg_index i JOIN pg_class x ON x.oid = i.indexrelid
                JOIN pg_class t ON t.oid = i.indrelid JOIN pg_am am ON am.oid = x.relam
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = i.indkey[0]
                WHERE t.relnamespace = 'public'::regnamespace AND NOT i.indisunique
                ORDER BY x.relname
                """)) {
            for (String[] expected : new String[][]{
                    {"departments", "manager_id", "idx_departments_manager_id"},
                    {"users", "department_id", "idx_users_department_id"},
                    {"users", "manager_id", "idx_users_manager_id"}}) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(expected[0]);
                assertThat(rows.getString(2)).isEqualTo(expected[1]);
                assertThat(rows.getString(3)).isEqualTo("btree");
                assertThat(rows.getBoolean(4)).isFalse();
                for (int col = 5; col <= 7; col++) assertThat(rows.getBoolean(col)).isTrue();
                assertThat(rows.getInt(8)).isEqualTo(1);
                assertThat(rows.getInt(9)).isEqualTo(1);
                assertThat(rows.getString(10)).isEqualTo(expected[2]);
            }
            assertThat(rows.next()).isFalse();
        }
    }

    private static void migrate(DataSource source, String path, int rollback) throws Exception {
        try (var c = source.getConnection(); var lb = new liquibase.Liquibase(path,
                new liquibase.resource.ClassLoaderResourceAccessor(), new liquibase.database.jvm.JdbcConnection(c))) {
            if (rollback == 0) lb.update(new liquibase.Contexts(), new liquibase.LabelExpression());
            else lb.rollback(rollback, new liquibase.Contexts(), new liquibase.LabelExpression());
            liquibase.Scope.getCurrentScope().getSingleton(liquibase.changelog.FastCheckService.class).clearCache();
        }
    }
}
