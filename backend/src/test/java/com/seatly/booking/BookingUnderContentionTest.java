package com.seatly.booking;

import com.seatly.event.Event;
import com.seatly.event.EventSeat;
import com.seatly.support.IntegrationTest;
import com.seatly.support.SeatlyFixtures;
import com.seatly.support.TestAccounts;
import com.seatly.account.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Eight customers, one seat, three strategies.
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
 * Eight ordinary callers start at the same instant and the results are counted.
 * The measurements for the unlocked version this replaced are kept in
 * {@code docs/concurrency.md}.
 *
 * <h2>The Redis guard is switched off here</h2>
 *
 * {@link SeatHoldGuard} would reject seven of these callers before they reached
 * the database, which is what it is for -- and it would also mean this test
 * proved nothing about the database. It is stubbed to the answer it gives when
 * Redis is unreachable: no opinion, carry on. Everything measured below is the
 * database's doing, which is the claim worth being able to make.
 */
class BookingUnderContentionTest extends IntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(BookingUnderContentionTest.class);

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
	private TestAccounts accounts;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoBean
	private SeatHoldGuard holdGuard;

	private Long eventId;
	private Long contestedSeatId;
	private List<Long> allSeatIds;
	private AppUser buyer;

	@BeforeEach
	void setUp() {
		// Exactly what the guard returns when Redis cannot be reached.
		given(holdGuard.tryClaimAll(any(), any())).willReturn(true);

		fixtures.wipe();
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		eventId = event.getId();
		contestedSeatId = seats.get(0).getId();
		allSeatIds = seats.stream().map(EventSeat::getId).toList();
		buyer = accounts.customer();
		accounts.actAs(buyer);
	}

	@AfterEach
	void tearDown() {
		accounts.signOut();
		fixtures.wipe();
	}

	/**
	 * The shipped implementation: lock the seat, then decide.
	 */
	@Test
	void pessimisticLockingSellsTheSeatExactlyOnce() throws Exception {
		AtomicInteger sold = new AtomicInteger();
		AtomicInteger refused = new AtomicInteger();
		List<Throwable> failures = new CopyOnWriteArrayList<>();

		runTogether(number -> {
			try {
				bookingService.hold(new BookingRequest(eventId, List.of(contestedSeatId)));
				sold.incrementAndGet();
			}
			catch (SeatUnavailableException politeRefusal) {
				refused.incrementAndGet();
			}
			catch (Throwable problem) {
				failures.add(problem);
			}
		});

		long claims = bookingSeats.countByEventSeatId(contestedSeatId);

		report("pessimistic lock (shipped)", sold.get(), refused.get(), failures.size(), claims);

		assertThat(failures).as("nobody should see an error").isEmpty();
		assertThat(sold.get()).as("customers who got the seat").isEqualTo(1);
		assertThat(refused.get()).as("customers told it was taken").isEqualTo(CONTENDERS - 1);
		assertThat(claims).as("claims on the seat").isEqualTo(1);
		assertThat(bookingSeats.findDoubleBookedSeatIds()).isEmpty();
	}

	/**
	 * The alternative: no lock, but a version check on the update.
	 * <p>
	 * Also correct -- the losing transactions fail rather than overwrite. The
	 * difference is what the losers spend before they find out: they do the whole
	 * booking and throw it away, where a pessimistic caller simply waits.
	 */
	@Test
	void optimisticLockingAlsoPreventsIt() throws Exception {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		AtomicInteger sold = new AtomicInteger();
		AtomicInteger lostTheRace = new AtomicInteger();
		List<Throwable> failures = new CopyOnWriteArrayList<>();

		runTogether(number -> {
			try {
				transaction.executeWithoutResult(status -> {
					var seat = jdbc.queryForMap(
							"select status, version from event_seat where id = ?", contestedSeatId);

					if (!"AVAILABLE".equals(seat.get("status"))) {
						lostTheRace.incrementAndGet();
						return;
					}

					// The version the row carried when the decision was made.
					long expectedVersion = ((Number) seat.get("version")).longValue();
					int updated = jdbc.update("""
							update event_seat
							set status = 'SOLD', version = version + 1
							where id = ? and version = ?
							""", contestedSeatId, expectedVersion);

					if (updated == 0) {
						// Somebody else moved the row on. Nothing was overwritten.
						lostTheRace.incrementAndGet();
						status.setRollbackOnly();
						return;
					}

					insertBooking(number, "SEAT-OPT" + number);
					sold.incrementAndGet();
				});
			}
			catch (Throwable problem) {
				failures.add(problem);
			}
		});

		long claims = bookingSeats.countByEventSeatId(contestedSeatId);

		report("optimistic version check", sold.get(), lostTheRace.get(), failures.size(), claims);

		assertThat(failures).isEmpty();
		assertThat(sold.get()).isEqualTo(1);
		assertThat(claims).isEqualTo(1);
	}

	/**
	 * And the belt to the application's braces.
	 * <p>
	 * This runs the unlocked sequence that used to sell the same chair eight
	 * times -- check, then write, with nothing held in between. The partial
	 * unique index refuses every claim after the first, so even an implementation
	 * that gets the concurrency wrong cannot leave the data wrong.
	 */
	@Test
	void theDatabaseRefusesASecondClaimEvenWithoutAnyLocking() throws Exception {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		AtomicInteger sold = new AtomicInteger();
		AtomicInteger rejectedByTheDatabase = new AtomicInteger();
		List<Throwable> failures = new CopyOnWriteArrayList<>();

		runTogether(number -> {
			try {
				transaction.executeWithoutResult(status -> {
					String seatStatus = jdbc.queryForObject(
							"select status from event_seat where id = ?", String.class, contestedSeatId);
					if (!"AVAILABLE".equals(seatStatus)) {
						return;
					}

					jdbc.update("update event_seat set status = 'SOLD' where id = ?", contestedSeatId);
					insertBooking(number, "SEAT-RAW" + number);
					sold.incrementAndGet();
				});
			}
			catch (DataIntegrityViolationException refusedByConstraint) {
				rejectedByTheDatabase.incrementAndGet();
			}
			catch (Throwable problem) {
				failures.add(problem);
			}
		});

		long claims = bookingSeats.countByEventSeatId(contestedSeatId);

		report("no locking, constraint only", sold.get(), rejectedByTheDatabase.get(),
				failures.size(), claims);

		assertThat(failures).isEmpty();
		assertThat(rejectedByTheDatabase.get())
				.as("claims the database threw out")
				.isGreaterThan(0);
		assertThat(claims).as("live claims on the seat").isEqualTo(1);
		assertThat(bookingSeats.findDoubleBookedSeatIds()).isEmpty();
	}

	/**
	 * Two bookings, overlapping seats, requested in opposite orders.
	 * <p>
	 * The case the {@code order by} in {@code lockAllById} exists for. Without
	 * it, one transaction locks A then wants B while the other locks B then wants
	 * A, and PostgreSQL kills one of them.
	 */
	@Test
	void overlappingMultiSeatBookingsDoNotDeadlock() throws Exception {
		List<Long> ascending = allSeatIds.stream().sorted().toList();
		List<Long> descending = ascending.reversed();
		AtomicInteger sold = new AtomicInteger();
		AtomicInteger refused = new AtomicInteger();
		List<Throwable> failures = new CopyOnWriteArrayList<>();

		CyclicBarrier startTogether = new CyclicBarrier(2);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			for (int number = 0; number < 2; number++) {
				int contender = number;
				List<Long> order = contender == 0 ? ascending : descending;
				pool.submit(() -> {
					try {
						startTogether.await(10, TimeUnit.SECONDS);
						accounts.actAs(buyer);
						bookingService.hold(new BookingRequest(eventId, order));
						sold.incrementAndGet();
					}
					catch (SeatUnavailableException politeRefusal) {
						refused.incrementAndGet();
					}
					catch (Throwable problem) {
						failures.add(problem);
					}
				});
			}
		}

		report("overlapping sets, opposite order", sold.get(), refused.get(), failures.size(),
				bookingSeats.countByEventSeatId(contestedSeatId));

		assertThat(failures).as("no deadlock, no error").isEmpty();
		assertThat(sold.get()).isEqualTo(1);
		assertThat(refused.get()).isEqualTo(1);
	}

	private void insertBooking(int number, String reference) {
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
	}

	private void report(String strategy, int sold, int turnedAway, int failures, long claims) {
		log.warn("""

						=== {} : {} customers, one seat ===
						  sold the seat            : {}
						  turned away              : {}
						  failures                 : {}
						  live claims on the seat  : {}
						""",
				strategy, CONTENDERS, sold, turnedAway, failures, claims);
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
					// The security context is thread-bound, so each contender has
					// to establish its own before calling the service.
					accounts.actAs(buyer);
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
