package com.seatly.event.stream;

import com.seatly.account.AppUser;
import com.seatly.booking.BookingRequest;
import com.seatly.booking.BookingService;
import com.seatly.booking.BookingView;
import com.seatly.booking.SeatUnavailableException;
import com.seatly.event.Event;
import com.seatly.event.EventSeat;
import com.seatly.event.EventSeatStatus;
import com.seatly.support.IntegrationTest;
import com.seatly.support.SeatlyFixtures;
import com.seatly.support.TestAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * A committed seat change, all the way out through Redis and back to a browser.
 *
 * <h2>The whole path, not a stub of it</h2>
 *
 * A recording emitter is subscribed through the real registry, so what these
 * tests observe has genuinely been published to Redis, read back by the
 * subscriber, and handed to a watcher, the same journey a customer's browser
 * puts it through.
 *
 * <h2>Not transactional</h2>
 *
 * The listener that broadcasts waits for a commit, so a test that rolled back
 * would see nothing at all. That is the point of one of the tests here, and
 * would silently disable the others.
 */
class SeatUpdateBroadcastTests extends IntegrationTest {

	@Autowired
	private BookingService bookings;

	@Autowired
	private SeatStreamRegistry registry;

	@Autowired
	private SeatlyFixtures fixtures;

	@Autowired
	private TestAccounts accounts;

	private AppUser customer;
	private Event event;
	private List<EventSeat> seats;
	private RecordingEmitter browser;

	@BeforeEach
	void setUp() {
		fixtures.wipe();
		customer = accounts.customer();
		accounts.actAs(customer);
		event = fixtures.onSaleEvent();
		seats = fixtures.seatsOf(event);

		browser = new RecordingEmitter();
		registry.register(event.getId(), browser);
	}

	@AfterEach
	void tearDown() {
		accounts.signOut();
	}

	@Test
	void holdingASeatReachesTheBrowserViaRedis() {
		bookings.hold(new BookingRequest(event.getId(), List.of(seats.get(0).getId())));

		awaitStatusOf(seats.get(0), EventSeatStatus.HELD);
	}

	@Test
	void cancellingSendsTheSeatBackAsAvailable() {
		BookingView held = bookings.hold(
				new BookingRequest(event.getId(), List.of(seats.get(0).getId())));
		awaitStatusOf(seats.get(0), EventSeatStatus.HELD);

		bookings.cancel(held.reference());

		awaitStatusOf(seats.get(0), EventSeatStatus.AVAILABLE);
	}

	@Test
	void everySeatOfAMultiSeatHoldIsAnnounced() {
		bookings.hold(new BookingRequest(event.getId(),
				List.of(seats.get(0).getId(), seats.get(1).getId())));

		awaitStatusOf(seats.get(0), EventSeatStatus.HELD);
		awaitStatusOf(seats.get(1), EventSeatStatus.HELD);
	}

	/** A change for one event must not reach a browser watching another. */
	@Test
	void aBrowserWatchingADifferentEventHearsNothing() throws Exception {
		RecordingEmitter elsewhere = new RecordingEmitter();
		registry.register(event.getId() + 1_000, elsewhere);

		bookings.hold(new BookingRequest(event.getId(), List.of(seats.get(0).getId())));
		awaitStatusOf(seats.get(0), EventSeatStatus.HELD);

		assertThat(elsewhere.received).isEmpty();
	}

	/**
	 * The reason the listener waits for the commit.
	 * <p>
	 * This hold fails on its second seat, so the transaction rolls back and the
	 * first seat was never taken. Telling browsers otherwise would leave a seat
	 * looking held until somebody reloaded, and there is no unsending an SSE
	 * message.
	 */
	@Test
	void aRolledBackChangeIsNeverBroadcast() throws Exception {
		// Somebody else already holds the second seat.
		AppUser other = accounts.customer();
		accounts.actAs(other);
		bookings.hold(new BookingRequest(event.getId(), List.of(seats.get(1).getId())));
		awaitStatusOf(seats.get(1), EventSeatStatus.HELD);
		accounts.actAs(customer);
		browser.received.clear();

		assertThatThrownBy(() -> bookings.hold(new BookingRequest(
				event.getId(), List.of(seats.get(0).getId(), seats.get(1).getId()))))
				.isInstanceOf(SeatUnavailableException.class);

		// Long enough that a broadcast would certainly have arrived by now.
		Thread.sleep(1500);
		assertThat(latestFor(seats.get(0))).as("the seat that was never taken").isEmpty();
		assertThat(fixtures.seatsOf(event).get(0).getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
	}

	private void awaitStatusOf(EventSeat seat, EventSeatStatus expected) {
		await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
				assertThat(latestFor(seat)).isPresent()
						.get().extracting(SeatChanged::status).isEqualTo(expected));
	}

	private Optional<SeatChanged> latestFor(EventSeat seat) {
		return browser.received.stream()
				.filter(change -> change.eventSeatId().equals(seat.getId()))
				.reduce((first, second) -> second);
	}

	/** A browser, as far as the registry is concerned. */
	private static final class RecordingEmitter extends SseEmitter {

		private final List<SeatChanged> received = new CopyOnWriteArrayList<>();

		@Override
		public void send(SseEventBuilder builder) {
			builder.build().stream()
					.map(DataWithMediaType::getData)
					.filter(SeatChanged.class::isInstance)
					.map(SeatChanged.class::cast)
					.forEach(received::add);
		}
	}

}
