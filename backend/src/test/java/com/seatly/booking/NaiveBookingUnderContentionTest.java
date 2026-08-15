package com.seatly.booking;

import com.seatly.event.Event;
import com.seatly.event.EventSeat;
import com.seatly.support.IntegrationTest;
import com.seatly.support.SeatlyFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the naive booking service actually does when eight customers ask for the
 * same seat at the same instant.
 *
 * <h2>Why this test is not transactional</h2>
 *
 * Every other integration test here rolls back. This one must not: a race
 * between transactions cannot exist inside a single transaction. Each contender
 * runs its own committed transaction, and the tables are truncated around the
 * test instead.
 *
 * <h2>Nothing here is contrived</h2>
 *
 * No injected delay, no paused thread, no test-only hook in the production code.
 * Eight ordinary callers start at the same moment and the results are counted.
 */
class NaiveBookingUnderContentionTest extends IntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(NaiveBookingUnderContentionTest.class);

	/** Kept below the connection pool size so every contender is genuinely concurrent. */
	private static final int CONTENDERS = 8;

	private static final long PRICE_MINOR = 120_000L;

	@Autowired
	private BookingService bookingService;

	@Autowired
	private BookingSeatRepository bookingSeats;

	@Autowired
	private SeatlyFixtures fixtures;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private Long eventId;
	private Long contestedSeatId;

	@BeforeEach
	void setUp() {
		fixtures.wipe();
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		eventId = event.getId();
		contestedSeatId = seats.get(0).getId();
	}

	@AfterEach
	void tearDown() {
		fixtures.wipe();
	}

	/**
	 * Outcome one: the service falls over.
	 * <p>
	 * Hibernate flushes the {@code booking} and {@code booking_seat} inserts
	 * before the {@code event_seat} update. The insert takes a foreign-key lock
	 * on the seat row, the update wants that row exclusively, and every
	 * transaction ends up waiting on a lock another one is holding. PostgreSQL
	 * detects the cycle and kills all but one:
	 * <pre>
	 * deadlock detected
	 *   Detail: Process 70 waits for ShareLock on transaction 751; blocked by process 63.
	 *           Process 63 waits for ShareLock on transaction 752; blocked by process 70.
	 *   Where: while locking tuple (0,1) in relation "event_seat"
	 * </pre>
	 */
	@Test
	void mostCustomersGetAnInternalErrorInsteadOfATicket() throws Exception {
		AtomicInteger sold = new AtomicInteger();
		AtomicInteger refused = new AtomicInteger();
		AtomicInteger deadlocked = new AtomicInteger();
		List<Throwable> other = new CopyOnWriteArrayList<>();

		runTogether(number -> {
			try {
				bookingService.book(new BookingRequest(
						eventId, List.of(contestedSeatId),
						"Customer " + number, "customer" + number + "@example.com"));
				sold.incrementAndGet();
			}
			catch (SeatUnavailableException politeRefusal) {
				refused.incrementAndGet();
			}
			catch (CannotAcquireLockException deadlock) {
				deadlocked.incrementAndGet();
			}
			catch (Throwable problem) {
				other.add(problem);
			}
		});

		long bookingsForThatSeat = bookingSeats.countByEventSeatId(contestedSeatId);

		log.warn("""

						=== Naive service, {} customers, one seat: as written ===
						  sold the seat            : {}
						  politely refused         : {}
						  killed by deadlock (500) : {}
						  other failures           : {}
						  bookings holding the seat: {}
						""",
				CONTENDERS, sold.get(), refused.get(), deadlocked.get(), other.size(), bookingsForThatSeat);

		assertThat(other).as("failures other than deadlock").isEmpty();
		assertThat(deadlocked.get())
				.as("customers who got an internal error rather than an answer")
				.isGreaterThan(0);
		assertThat(sold.get())
				.as("bookings created should match seats claimed")
				.isEqualTo((int) bookingsForThatSeat);
	}

	/**
	 * Outcome two: the service stops falling over, and starts lying.
	 * <p>
	 * This replays the same logic with one change -- the seat update is sent
	 * before the inserts, which is what you get by forcing a flush, or by writing
	 * the update as a query rather than through the persistence context. The
	 * deadlock disappears, because the transactions now queue on a single
	 * exclusive row lock instead of waiting on each other.
	 * <p>
	 * Every one of them then sells the same seat. The statements are written out
	 * here so the ordering is visible rather than inferred from Hibernate's
	 * behaviour.
	 */
	@Test
	void reorderingTheWritesTradesTheCrashForASilentDoubleSale() throws Exception {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		AtomicInteger sold = new AtomicInteger();
		AtomicInteger refused = new AtomicInteger();
		List<Throwable> failures = new CopyOnWriteArrayList<>();

		runTogether(number -> {
			try {
				transaction.executeWithoutResult(status -> {
					String seatStatus = jdbc.queryForObject(
							"select status from event_seat where id = ?", String.class, contestedSeatId);

					// ---- the check ----
					if (!"AVAILABLE".equals(seatStatus)) {
						refused.incrementAndGet();
						return;
					}

					// ---- the write, seat first this time ----
					jdbc.update("update event_seat set status = 'SOLD' where id = ?", contestedSeatId);

					String reference = "SEAT-QUEUE" + number;
					jdbc.update("""
							insert into booking
							  (reference, event_id, customer_name, customer_email, status,
							   total_minor, currency, confirmed_at)
							values (?, ?, ?, ?, 'CONFIRMED', ?, 'INR', now())
							""", reference, eventId, "Customer " + number,
							"customer" + number + "@example.com", PRICE_MINOR);

					Long bookingId = jdbc.queryForObject(
							"select id from booking where reference = ?", Long.class, reference);
					jdbc.update("""
							insert into booking_seat (booking_id, event_seat_id, price_minor)
							values (?, ?, ?)
							""", bookingId, contestedSeatId, PRICE_MINOR);

					sold.incrementAndGet();
				});
			}
			catch (Throwable problem) {
				failures.add(problem);
			}
		});

		long bookingsForThatSeat = bookingSeats.countByEventSeatId(contestedSeatId);
		List<Long> doubleBooked = bookingSeats.findDoubleBookedSeatIds();

		log.warn("""

						=== Naive service, {} customers, one seat: writes reordered ===
						  told "the seat is yours" : {}
						  politely refused         : {}
						  failures of any kind     : {}
						  bookings holding the seat: {}
						  seats sold more than once: {}
						""",
				CONTENDERS, sold.get(), refused.get(), failures.size(),
				bookingsForThatSeat, doubleBooked.size());

		assertThat(failures).as("nothing failed -- that is the problem").isEmpty();
		assertThat(sold.get())
				.as("customers told the seat was theirs")
				.isGreaterThan(1);
		assertThat(bookingsForThatSeat)
				.as("bookings holding the same seat")
				.isGreaterThan(1);
		assertThat(doubleBooked)
				.as("seats sold more than once")
				.contains(contestedSeatId);
	}

	/** Starts every contender at the same instant and waits for all of them. */
	private void runTogether(Contender contender) throws Exception {
		CyclicBarrier startTogether = new CyclicBarrier(CONTENDERS);

		try (ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS)) {
			for (int number = 0; number < CONTENDERS; number++) {
				int contenderNumber = number;
				pool.submit(() -> {
					try {
						startTogether.await(10, TimeUnit.SECONDS);
					}
					catch (Exception barrierFailed) {
						throw new IllegalStateException(barrierFailed);
					}
					contender.attempt(contenderNumber);
				});
			}
		}
	}

	@FunctionalInterface
	private interface Contender {
		void attempt(int number);
	}

}
