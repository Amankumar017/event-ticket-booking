package com.seatly.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * The handful of numbers worth watching in production.
 *
 * <h2>Named in one place</h2>
 *
 * Metric names are an interface: dashboards and alerts are written against
 * them, and renaming one quietly breaks both. Keeping them here means the names
 * are visible together rather than scattered across the classes that happen to
 * record them.
 *
 * <h2>Tags are bounded</h2>
 *
 * Every tag value below comes from a fixed set. A tag carrying a seat id or a
 * booking reference would create a new time series per seat: the cardinality
 * explosion that turns a metrics backend into an outage.
 */
@Component
public class SeatlyMetrics {

	private final MeterRegistry registry;

	public SeatlyMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	/** How a hold attempt ended. */
	public enum HoldOutcome {
		GRANTED, REFUSED, FAILED
	}

	public void holdAttempt(HoldOutcome outcome, long nanos) {
		Counter.builder("seatly.holds")
				.description("Attempts to hold seats, by outcome")
				.tag("outcome", outcome.name().toLowerCase())
				.register(registry)
				.increment();

		Timer.builder("seatly.hold.duration")
				.description("How long a hold attempt took, including any wait for the seat lock")
				.tag("outcome", outcome.name().toLowerCase())
				.publishPercentileHistogram()
				.register(registry)
				.record(nanos, TimeUnit.NANOSECONDS);
	}

	public void seatsSold(int count) {
		Counter.builder("seatly.seats.sold")
				.description("Seats that have been paid for")
				.register(registry)
				.increment(count);
	}

	public void seatsReleased(int count, String reason) {
		Counter.builder("seatly.seats.released")
				.description("Seats returned to sale, by reason")
				.tag("reason", reason)
				.register(registry)
				.increment(count);
	}

	/** Webhook deliveries, split by what they turned out to be. */
	public void webhookDelivery(String outcome) {
		Counter.builder("seatly.webhooks")
				.description("Payment webhook deliveries, by outcome")
				.tag("outcome", outcome)
				.register(registry)
				.increment();
	}

	public void guardDecision(String decision) {
		Counter.builder("seatly.hold.guard")
				.description("Redis seat-hold guard decisions")
				.tag("decision", decision)
				.register(registry)
				.increment();
	}

}
