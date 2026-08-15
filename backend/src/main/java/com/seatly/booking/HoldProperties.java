package com.seatly.booking;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How long a customer gets to pay, and how often lapsed holds are cleaned up.
 *
 * @param ttl        how long seats stay held before the hold lapses
 * @param sweepEvery how often the expiry job runs
 * @param batchSize  how many lapsed bookings one sweep will release
 */
@ConfigurationProperties(prefix = "seatly.hold")
public record HoldProperties(Duration ttl, Duration sweepEvery, int batchSize) {

	public HoldProperties {
		if (ttl == null || ttl.isNegative() || ttl.isZero()) {
			throw new IllegalArgumentException("seatly.hold.ttl must be a positive duration");
		}
		if (batchSize <= 0) {
			throw new IllegalArgumentException("seatly.hold.batch-size must be positive");
		}
	}

}
