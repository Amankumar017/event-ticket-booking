package com.seatly.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfiguration {

	/**
	 * The application's only source of the current time.
	 * <p>
	 * Scattered {@code Instant.now()} calls are impossible to test around: a hold
	 * that lapses after five minutes would need a test that actually waits five
	 * minutes. Injecting the clock turns that into a fixed clock and an
	 * assertion. UTC because storing and comparing instants in a local zone is
	 * how off-by-an-hour bugs arrive twice a year.
	 */
	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

}
