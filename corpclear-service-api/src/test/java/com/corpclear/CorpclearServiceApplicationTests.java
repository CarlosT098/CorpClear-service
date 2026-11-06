package com.corpclear;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import javax.sql.DataSource;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class CorpclearServiceApplicationTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
			// Bound reads against the paused database without changing production timeouts.
			.withUrlParam("socketTimeout", "2");

	@Autowired
	DataSource dataSource;

	@Value("${local.server.port}")
	int port;

	private final HttpClient http = HttpClient.newHttpClient();

	@Test
	void healthReflectsDatabaseOutageAndRecovery() throws Exception {
		assertHealth(200, "UP");
		var docker = postgres.getDockerClient();
		var containerId = postgres.getContainerId();
		// Pause preserves the mapped port while making the database unresponsive.
		docker.pauseContainerCmd(containerId).exec();
		try {
			assertHealth(503, "DOWN");
		} finally {
			docker.unpauseContainerCmd(containerId).exec();
		}
		await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> assertHealth(200, "UP"));
	}

	private void assertHealth(int statusCode, String status) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
				.timeout(Duration.ofSeconds(45)).GET().build();
		var response = http.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(statusCode);
		Map<String, Object> body = JsonPath.parse(response.body()).read("$");
		assertThat(body).containsEntry("status", status).doesNotContainKeys("components", "details");
	}

	@Test
	void contextConnectsToPostgresql17() throws SQLException {
		try (var connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
			assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
			assertThat(connection.isValid(5)).isTrue();
		}
	}

}
