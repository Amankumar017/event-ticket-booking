package com.seatly;

import com.seatly.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole application against throwaway Postgres and Redis containers
 * and checks that the schema was built by Flyway rather than by anything else.
 */
class SeatlyApplicationTests extends IntegrationTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void contextLoads() {
		assertThat(jdbc).isNotNull();
	}

	@Test
	void flywayAppliedEveryMigration() {
		Integer applied = jdbc.queryForObject(
				"select count(*) from flyway_schema_history where success", Integer.class);

		assertThat(applied).isNotNull().isGreaterThanOrEqualTo(2);
	}

	@Test
	void baselineDefinesTheSharedUpdatedAtTrigger() {
		Integer functions = jdbc.queryForObject(
				"select count(*) from pg_proc where proname = 'set_updated_at'", Integer.class);

		assertThat(functions).isEqualTo(1);
	}

	/**
	 * Hibernate runs with {@code ddl-auto: validate}, so the context would not
	 * have started at all if an entity disagreed with the migrated schema. This
	 * asserts the other half: that the tables exist under the names expected.
	 */
	@Test
	void everyDomainTableWasMigrated() {
		List<String> tables = jdbc.queryForList(
				"select table_name from information_schema.tables where table_schema = 'public'",
				String.class);

		assertThat(tables).contains(
				"venue", "seat_section", "seat", "event", "event_seat", "booking", "booking_seat");
	}

}
