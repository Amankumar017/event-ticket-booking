package com.seatly;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole application against throwaway Postgres and Redis containers.
 * <p>
 * The containers are started once for the class and wired in by
 * {@code @ServiceConnection}, which overrides the datasource and Redis
 * properties at runtime -- so these tests never touch the developer's local
 * database and never need a separate test configuration file.
 */
@SpringBootTest
@Testcontainers
class SeatlyApplicationTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

	@Container
	@ServiceConnection(name = "redis")
	static final GenericContainer<?> REDIS =
			new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void contextLoads() {
		assertThat(jdbc).isNotNull();
	}

	@Test
	void flywayAppliedTheBaselineMigration() {
		Integer applied = jdbc.queryForObject(
				"select count(*) from flyway_schema_history where success", Integer.class);

		assertThat(applied).isNotNull().isGreaterThanOrEqualTo(1);
	}

	@Test
	void baselineDefinesTheSharedUpdatedAtTrigger() {
		Integer functions = jdbc.queryForObject(
				"select count(*) from pg_proc where proname = 'set_updated_at'", Integer.class);

		assertThat(functions).isEqualTo(1);
	}

}
