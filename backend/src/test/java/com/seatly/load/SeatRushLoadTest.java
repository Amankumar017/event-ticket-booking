package com.seatly.load;

import com.seatly.account.AppUser;
import com.seatly.booking.BookingRepository;
import com.seatly.booking.BookingRequest;
import com.seatly.booking.BookingSeatRepository;
import com.seatly.booking.BookingService;
import com.seatly.booking.SeatUnavailableException;
import com.seatly.event.Event;
import com.seatly.event.EventSeat;
import com.seatly.support.IntegrationTest;
import com.seatly.support.SeatlyFixtures;
import com.seatly.support.TestAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens when a popular event goes on sale.
 *
 * <h2>Running it</h2>
 *
 * <pre>./mvnw test -Dtest=SeatRushLoadTest -DexcludedTestGroups=none</pre>
 *
 * Tagged {@code load} and excluded from the ordinary suite: it takes far longer
 * than a unit test and its numbers depend on the machine, which is not something
 * a build should fail over.
 *
 * <h2>What it measures, and what it proves</h2>
 *
 * The numbers -- throughput, latency -- describe this laptop and are worth
 * little on their own. The assertions are the point: whatever the numbers turn
 * out to be, no seat is ever claimed twice and no request fails with an error.
 * A run that is slower than the last one is information; a run that oversells a
 * seat is a bug.
 */
@Tag("load")
class SeatRushLoadTest extends IntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(SeatRushLoadTest.class);

	/** Customers arriving at once. Well above the ten-connection pool, on purpose. */
	private static final int CUSTOMERS = 200;

	@Autowired
	private BookingService bookings;

	@Autowired
	private BookingRepository bookingRepository;

	@Autowired
	private BookingSeatRepository bookingSeats;

	@Autowired
	private SeatlyFixtures fixtures;

	@Autowired
	private TestAccounts accounts;

	private Event event;
	private List<EventSeat> seats;
	private List<AppUser> customers;

	@BeforeEach
	void setUp() {
		fixtures.wipe();
		event = fixtures.largeVenueEvent(CUSTOMERS);
		seats = fixtures.seatsOf(event);

		customers = new ArrayList<>(CUSTOMERS);
		for (int i = 0; i < CUSTOMERS; i++) {
			customers.add(accounts.customer());
		}
	}

	@AfterEach
	void tearDown() {
		accounts.signOut();
		fixtures.wipe();
	}

	/**
	 * Everybody wants a different seat. This is the throughput case: the seats do
	 * not contend, so what is being measured is how fast the whole path runs.
	 */
	@Test
	void aHallSellingOutAtOnce() throws Exception {
		Outcome outcome = rush(customer -> customer);

		report("one seat each, %d customers".formatted(CUSTOMERS), outcome);

		assertThat(outcome.failures).as("errors").isEmpty();
		assertThat(outcome.granted.get()).as("holds granted").isEqualTo(CUSTOMERS);
		assertThat(bookingSeats.findDoubleBookedSeatIds()).as("seats claimed twice").isEmpty();
		assertThat(bookingRepository.count()).isEqualTo(CUSTOMERS);
	}

	/**
	 * Everybody wants the same seat. This is the contention case, and the one the
	 * whole project exists for -- stage 5's eight contenders, at twenty-five
	 * times the size.
	 */
	@Test
	void twoHundredCustomersWantingOneSeat() throws Exception {
		Outcome outcome = rush(customer -> 0);

		report("all %d after one seat".formatted(CUSTOMERS), outcome);

		assertThat(outcome.failures).as("errors").isEmpty();
		assertThat(outcome.granted.get()).as("holds granted").isEqualTo(1);
		assertThat(outcome.refused.get()).as("honest refusals").isEqualTo(CUSTOMERS - 1);
		assertThat(bookingSeats.countByEventSeatId(seats.get(0).getId())).isEqualTo(1);
		assertThat(bookingSeats.findDoubleBookedSeatIds()).isEmpty();
	}

	/** Runs every customer at once, each asking for the seat the mapper picks. */
	private Outcome rush(SeatChoice choice) throws Exception {
		Outcome outcome = new Outcome();
		CyclicBarrier startTogether = new CyclicBarrier(CUSTOMERS);
		List<Long> latenciesNanos = new CopyOnWriteArrayList<>();

		long startedAt = System.nanoTime();
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int number = 0; number < CUSTOMERS; number++) {
				int customerNumber = number;
				pool.submit(() -> {
					accounts.actAs(customers.get(customerNumber));
					try {
						startTogether.await(30, TimeUnit.SECONDS);
					}
					catch (Exception barrierFailed) {
						throw new IllegalStateException(barrierFailed);
					}

					long attemptStarted = System.nanoTime();
					try {
						bookings.hold(new BookingRequest(event.getId(),
								List.of(seats.get(choice.seatIndexFor(customerNumber)).getId())));
						outcome.granted.incrementAndGet();
					}
					catch (SeatUnavailableException refused) {
						outcome.refused.incrementAndGet();
					}
					catch (Throwable problem) {
						outcome.failures.add(problem);
					}
					finally {
						latenciesNanos.add(System.nanoTime() - attemptStarted);
					}
				});
			}
		}
		outcome.wallClockMillis = (System.nanoTime() - startedAt) / 1_000_000;
		outcome.latenciesMillis = latenciesNanos.stream().sorted()
				.map(nanos -> nanos / 1_000_000.0).toList();
		return outcome;
	}

	private void report(String scenario, Outcome outcome) {
		log.warn("""

						=== {} ===
						  wall clock             : {} ms
						  throughput             : {} holds/sec
						  granted                : {}
						  refused                : {}
						  errors                 : {}
						  latency p50 / p95 / p99: {} / {} / {} ms
						  slowest                : {} ms
						  seats claimed twice    : {}
						""",
				scenario,
				outcome.wallClockMillis,
				String.format("%.0f", CUSTOMERS / (outcome.wallClockMillis / 1000.0)),
				outcome.granted.get(),
				outcome.refused.get(),
				outcome.failures.size(),
				format(outcome.percentile(50)), format(outcome.percentile(95)),
				format(outcome.percentile(99)), format(outcome.percentile(100)),
				bookingSeats.findDoubleBookedSeatIds().size());
	}

	private static String format(double millis) {
		return String.format("%.0f", millis);
	}

	@FunctionalInterface
	private interface SeatChoice {
		int seatIndexFor(int customerNumber);
	}

	private static final class Outcome {
		private final AtomicInteger granted = new AtomicInteger();
		private final AtomicInteger refused = new AtomicInteger();
		private final List<Throwable> failures = new CopyOnWriteArrayList<>();
		private long wallClockMillis;
		private List<Double> latenciesMillis = List.of();

		private double percentile(int percentile) {
			if (latenciesMillis.isEmpty()) {
				return 0;
			}
			int index = Math.min(latenciesMillis.size() - 1,
					(int) Math.ceil(percentile / 100.0 * latenciesMillis.size()) - 1);
			return latenciesMillis.get(Math.max(index, 0));
		}
	}

}
