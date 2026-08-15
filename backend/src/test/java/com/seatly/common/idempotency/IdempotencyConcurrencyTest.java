package com.seatly.common.idempotency;

import com.seatly.account.AppUser;
import com.seatly.support.IntegrationTest;
import com.seatly.support.SeatlyFixtures;
import com.seatly.support.TestAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens when the same idempotency key arrives several times at once.
 *
 * <h2>Why this needs threads</h2>
 *
 * The interesting case is not a client retrying after a timeout: that is
 * sequential, and covered by the API tests. It is a client that retried while
 * the first attempt was still running, which is exactly what an impatient
 * user's second click looks like from the server's side.
 */
class IdempotencyConcurrencyTest extends IntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(IdempotencyConcurrencyTest.class);

	private static final int CALLERS = 8;

	@Autowired
	private IdempotencyService idempotency;

	@Autowired
	private IdempotencyRecordRepository records;

	@Autowired
	private SeatlyFixtures fixtures;

	@Autowired
	private TestAccounts accounts;

	private AppUser customer;

	@BeforeEach
	void setUp() {
		fixtures.wipe();
		customer = accounts.customer();
	}

	@AfterEach
	void tearDown() {
		fixtures.wipe();
	}

	@Test
	void theWorkRunsOnceHoweverManyCallersArriveTogether() throws Exception {
		String key = UUID.randomUUID().toString();
		Map<String, String> request = Map.of("bookingReference", "SEAT-ABCD2345");

		AtomicInteger executions = new AtomicInteger();
		AtomicInteger replayed = new AtomicInteger();
		AtomicInteger toldToWait = new AtomicInteger();
		List<Throwable> failures = new CopyOnWriteArrayList<>();

		CyclicBarrier startTogether = new CyclicBarrier(CALLERS);
		try (ExecutorService pool = Executors.newFixedThreadPool(CALLERS)) {
			for (int caller = 0; caller < CALLERS; caller++) {
				pool.submit(() -> {
					try {
						startTogether.await(10, TimeUnit.SECONDS);
						String answer = idempotency.runOnce(key, customer.getId(), request, String.class,
								() -> {
									executions.incrementAndGet();
									// Long enough that the others are certainly
									// inside the same window.
									sleep();
									return "the-one-answer";
								});
						if ("the-one-answer".equals(answer)) {
							replayed.incrementAndGet();
						}
					}
					catch (IdempotencyConflictException stillRunning) {
						toldToWait.incrementAndGet();
					}
					catch (Throwable problem) {
						failures.add(problem);
					}
				});
			}
		}

		log.warn("""

						=== {} callers, one idempotency key ===
						  work actually executed : {}
						  got the answer         : {}
						  told to try again      : {}
						  unexpected failures    : {}
						  key rows stored        : {}
						""",
				CALLERS, executions.get(), replayed.get(), toldToWait.get(), failures.size(),
				records.count());

		assertThat(failures).isEmpty();
		assertThat(executions.get()).as("times the work ran").isEqualTo(1);
		assertThat(records.count()).as("key rows").isEqualTo(1);
		// Everyone either got the answer or was told to come back; nobody did the
		// work twice and nobody got an error.
		assertThat(replayed.get() + toldToWait.get()).isEqualTo(CALLERS);
	}

	private void sleep() {
		try {
			Thread.sleep(300);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

}
