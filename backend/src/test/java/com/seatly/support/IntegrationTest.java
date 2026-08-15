package com.seatly.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for tests that need the real application against real backing
 * services.
 * <p>
 * The containers are static and started once in a class initialiser rather than
 * managed by {@code @Testcontainers}, which would start and stop a fresh pair
 * for every test class. One Postgres shared by the whole suite turns roughly
 * fifteen seconds of container startup per class into fifteen seconds total; the
 * containers are torn down by Ryuk when the JVM exits.
 * <p>
 * Isolation comes from transactions instead: subclasses annotated
 * {@code @Transactional} roll back whatever they wrote when the test ends.
 */
@SpringBootTest
public abstract class IntegrationTest {

	@ServiceConnection
	protected static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

	@ServiceConnection(name = "redis")
	protected static final GenericContainer<?> REDIS =
			new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);

	static {
		POSTGRES.start();
		REDIS.start();
	}

}
