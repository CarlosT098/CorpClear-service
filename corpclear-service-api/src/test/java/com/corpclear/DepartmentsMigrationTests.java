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

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class DepartmentsMigrationTests {
	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	@Autowired
	DataSource dataSource;

	@org.junit.jupiter.api.BeforeEach
	void clearDepartments() throws SQLException {
		try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
			statement.executeUpdate("UPDATE departments SET manager_id = NULL");
            statement.executeUpdate("DELETE FROM users");
            statement.executeUpdate("DELETE FROM departments");
		}
	}

	@Test
	void createsDepartmentsWithExplicitIdentityAndDates() throws SQLException {
		try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
			statement.executeUpdate("INSERT INTO departments (id, name, created_at, updated_at) VALUES ('11111111-1111-1111-1111-111111111111', 'Finanças', '2026-11-06T10:00:00-03:00', '2026-11-06T11:00:00-03:00')");
			try (var rows = statement.executeQuery("SELECT name, manager_id, created_at, updated_at, id FROM departments WHERE id = '11111111-1111-1111-1111-111111111111'")) {
				assertThat(rows.next()).isTrue();
				assertThat(rows.getString("name")).isEqualTo("Finanças");
				assertThat(rows.getObject("manager_id")).isNull();
				assertThat(rows.getObject("id")).isEqualTo(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"));
				assertThat(rows.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()).isEqualTo(java.time.Instant.parse("2026-11-06T14:00:00Z"));
				assertThat(rows.getObject("created_at", java.time.OffsetDateTime.class).toInstant())
						.isEqualTo(java.time.Instant.parse("2026-11-06T13:00:00Z"));
			}
		}
	}
	@Test
	void enforcesNamesAndRequiredValues() throws Exception {
		try (var c = dataSource.getConnection(); var st = c.createStatement()) {
			st.executeUpdate("DELETE FROM departments"); st.executeUpdate("INSERT INTO users VALUES ('55555555-5555-5555-5555-555555555555', 'Manager', 'manager@test', 'hash', 'MANAGER', 'ACTIVE', NULL, NULL, now(), now())");
			for (String name : new String[]{"Finanças", "Financas", "Recursos Humanos", "Recursos  Humanos", "x".repeat(100), "\tTab\t"}) {
				st.executeUpdate("INSERT INTO departments VALUES (gen_random_uuid(), '" + name + "', '55555555-5555-5555-5555-555555555555', now(), now())");
			}
			for (String name : new String[]{"FINANÇAS", "Finanças", "", "   ", " Leading", "Trailing ", "x".repeat(101)}) {
				String state = name.length() > 100 ? "22001" : name.equalsIgnoreCase("Finanças") ? "23505" : "23514";
				reject(st, "INSERT INTO departments VALUES (gen_random_uuid(), '" + name + "', NULL, now(), now())", state);
			}
			st.executeUpdate("INSERT INTO departments VALUES ('22222222-2222-2222-2222-222222222222', 'Legal', NULL, now(), now())");
			reject(st, "INSERT INTO departments VALUES ('22222222-2222-2222-2222-222222222222', 'Other', NULL, now(), now())", "23505");
			for (String values : new String[]{"NULL, 'Missing', NULL, now(), now()", "gen_random_uuid(), NULL, NULL, now(), now()", "gen_random_uuid(), 'Missing', NULL, NULL, now()", "gen_random_uuid(), 'Missing', NULL, now(), NULL"}) {
				reject(st, "INSERT INTO departments VALUES (" + values + ")", "23502");
			}
			for (String omitted : new String[]{"id", "created_at", "updated_at"}) {
				String columns = omitted.equals("id") ? "name, created_at, updated_at" : "id, name, " + (omitted.equals("created_at") ? "updated_at" : "created_at");
				String values = omitted.equals("id") ? "'Missing', now(), now()" : "gen_random_uuid(), 'Missing', now()";
				reject(st, "INSERT INTO departments (" + columns + ") VALUES (" + values + ")", "23502");
			}
		}
	}

	private static void reject(java.sql.Statement st, String sql, String state) {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> st.executeUpdate(sql))
				.isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(state));
	}

	@Test
	void schemaHasRequiredTypesAndNoGenerators() throws Exception {
		try (var c = dataSource.getConnection(); var st = c.createStatement()) {
			try (var rows = st.executeQuery("SELECT column_name, data_type, is_nullable, column_default, character_maximum_length FROM information_schema.columns WHERE table_name = 'departments' ORDER BY ordinal_position")) {
				for (String[] expected : new String[][]{{"id", "uuid", "NO"}, {"name", "character varying", "NO"}, {"manager_id", "uuid", "YES"}, {"created_at", "timestamp with time zone", "NO"}, {"updated_at", "timestamp with time zone", "NO"}}) {
					assertThat(rows.next()).isTrue();
					for (int i = 0; i < 3; i++) assertThat(rows.getString(i + 1)).isEqualTo(expected[i]);
					assertThat(rows.getString(4)).isNull();
					if (expected[0].equals("name")) assertThat(rows.getInt(5)).isEqualTo(100);
				}
				assertThat(rows.next()).isFalse();
			}
			try (var rows = st.executeQuery("SELECT count(*) FROM pg_trigger WHERE tgrelid = 'departments'::regclass AND NOT tgisinternal")) {
				rows.next(); assertThat(rows.getInt(1)).isZero();
			}
		}
	}

	@Test
	void reexecutionPreservesDataAndRollbackCanBeReapplied() throws Exception {
		try (var isolated = new PostgreSQLContainer("postgres:17-alpine")) {
			isolated.start();
			var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(isolated.getJdbcUrl(), isolated.getUsername(), isolated.getPassword());
			applyMigration(source);
			try (var c = source.getConnection(); var st = c.createStatement()) {
				st.executeUpdate("INSERT INTO departments VALUES (gen_random_uuid(), 'Preserved', NULL, now(), now())");
				applyMigration(source);
				try (var rows = st.executeQuery("SELECT name FROM departments")) {
					assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("Preserved"); assertThat(rows.next()).isFalse();
				}
				try (var rows = st.executeQuery("SELECT count(*) FROM databasechangelog")) {
					rows.next(); assertThat(rows.getInt(1)).isEqualTo(4);
				}
			}
			try (var c = source.getConnection(); var lb = new liquibase.Liquibase("db/changelog/db.changelog-master.xml", new liquibase.resource.ClassLoaderResourceAccessor(), new liquibase.database.jvm.JdbcConnection(c))) {
				lb.rollback(4, new liquibase.Contexts(), new liquibase.LabelExpression());
				// Simulate a fresh process after rollback: Liquibase caches successful fast checks per JVM.
				liquibase.Scope.getCurrentScope().getSingleton(liquibase.changelog.FastCheckService.class).clearCache();
			}
			try (var c = source.getConnection(); var st = c.createStatement()) {
				try (var rows = st.executeQuery("SELECT to_regclass('departments'), (SELECT count(*) FROM databasechangelog), (SELECT locked FROM databasechangeloglock WHERE id = 1), (SELECT count(*) FROM pg_class WHERE relname LIKE '%departments%'), (SELECT count(*) FROM pg_constraint WHERE conname LIKE '%departments%')")) {
					rows.next(); assertThat(rows.getString(1)).isNull(); assertThat(rows.getInt(2)).isZero(); assertThat(rows.getBoolean(3)).isFalse(); assertThat(rows.getInt(4)).isZero(); assertThat(rows.getInt(5)).isZero();
				}
			}
			applyMigration(source);
			try (var c = source.getConnection(); var st = c.createStatement(); var rows = st.executeQuery("SELECT count(*) FROM departments")) {
				rows.next(); assertThat(rows.getInt(1)).isZero();
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
