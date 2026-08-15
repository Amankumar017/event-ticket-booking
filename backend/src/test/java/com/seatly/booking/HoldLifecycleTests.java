package com.seatly.booking;

import com.seatly.event.Event;
import com.seatly.event.EventSeat;
import com.seatly.event.EventSeatRepository;
import com.seatly.event.EventSeatStatus;
import com.seatly.support.IntegrationTest;
import com.seatly.support.SeatlyFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * What happens to a hold as its deadline passes.
 *
 * <h2>Time is injected, not waited for</h2>
 *
 * The clock is a mock. Testing a five-minute hold by sleeping for five minutes
 * would be intolerable, and shortening the TTL to a second would test a
 * different system than the one that ships. Every question here is asked by
 * moving the clock, which is the whole reason {@code Clock} is a bean.
 *
 * <p>Not transactional: the expiry job runs each release in its own
 * {@code REQUIRES_NEW} transaction, which would deadlock against a test
 * transaction holding the same rows.
 *
 * <h2>The Redis guard is switched off here too</h2>
 *
 * Its TTL runs on wall-clock time inside Redis, which no injected clock can
 * move. Moving this clock forward six minutes leaves the guard key exactly where
 * it was, so a seat that has genuinely lapsed would still look taken to it. In
 * production the two run on the same real clock and expire together; here the
 * guard is stubbed to the answer it gives when Redis is unreachable, and the
 * database decides, which is the arrangement being tested.
 */
class HoldLifecycleTests extends IntegrationTest {

	private static final Instant NOON = Instant.parse("2026-08-15T12:00:00Z");

	@Autowired
	private BookingService bookingService;

	@Autowired
	private HoldExpiryJob expiryJob;

	@Autowired
	private EventSeatRepository eventSeats;

	@Autowired
	private BookingRepository bookings;

	@Autowired
	private BookingSeatRepository bookingSeats;

	@Autowired
	private SeatlyFixtures fixtures;

	@MockitoBean
	private Clock clock;

	@MockitoBean
	private SeatHoldGuard holdGuard;

	private Event event;
	private List<EventSeat> seats;

	@BeforeEach
	void setUp() {
		given(holdGuard.tryClaimAll(any(), any())).willReturn(true);
		fixtures.wipe();
		at(NOON);
		event = fixtures.onSaleEvent();
		seats = fixtures.seatsOf(event);
	}

	@Test
	void aHoldLapsesAtItsDeadlineWhetherOrNotTheJobHasRun() {
		BookingView held = hold();
		assertThat(held.expiresAt()).isEqualTo(NOON.plus(Duration.ofMinutes(5)));

		at(NOON.plus(Duration.ofMinutes(6)));

		// Nothing has tidied up: the row still says HELD.
		EventSeat seat = eventSeats.findById(seats.get(0).getId()).orElseThrow();
		assertThat(seat.getStatus()).isEqualTo(EventSeatStatus.HELD);
		// And the seat is free anyway, because the deadline says so.
		assertThat(seat.isClaimableAt(clock.instant())).isTrue();
	}

	@Test
	void somebodyElseCanTakeALapsedSeatBeforeTheJobRuns() {
		hold();
		at(NOON.plus(Duration.ofMinutes(6)));

		BookingView second = bookingService.hold(request());

		assertThat(second.status()).isEqualTo(BookingStatus.PENDING);
	}

	@Test
	void confirmingALapsedHoldIsRefused() {
		BookingView held = hold();
		at(NOON.plus(Duration.ofMinutes(6)));

		assertThatThrownBy(() -> bookingService.confirm(held.reference()))
				.isInstanceOf(SeatUnavailableException.class)
				.hasMessageContaining("expired");
	}

	@Test
	void confirmingJustInsideTheDeadlineWorks() {
		BookingView held = hold();
		at(NOON.plus(Duration.ofMinutes(4).plusSeconds(59)));

		assertThat(bookingService.confirm(held.reference()).status())
				.isEqualTo(BookingStatus.CONFIRMED);
	}

	@Test
	void theJobPutsTheStoredStateBackTheWayItShouldBe() {
		BookingView held = hold();
		at(NOON.plus(Duration.ofMinutes(6)));

		int released = expiryJob.releaseLapsedHolds();

		assertThat(released).isEqualTo(1);
		assertThat(eventSeats.findById(seats.get(0).getId()).orElseThrow())
				.satisfies(seat -> {
					assertThat(seat.getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
					assertThat(seat.getHeldUntil()).isNull();
				});
		assertThat(bookings.findByReference(held.reference()).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.EXPIRED);
		assertThat(bookingSeats.findDoubleBookedSeatIds()).isEmpty();
	}

	@Test
	void theJobLeavesLiveHoldsAlone() {
		hold();
		at(NOON.plus(Duration.ofMinutes(2)));

		assertThat(expiryJob.releaseLapsedHolds()).isZero();
		assertThat(eventSeats.findById(seats.get(0).getId()).orElseThrow().getStatus())
				.isEqualTo(EventSeatStatus.HELD);
	}

	/** The race the job has to survive: paid for, a moment before the sweep. */
	@Test
	void theJobWillNotExpireAHoldThatWasConfirmedInTime() {
		BookingView held = hold();
		at(NOON.plus(Duration.ofMinutes(4)));
		bookingService.confirm(held.reference());

		at(NOON.plus(Duration.ofMinutes(6)));
		int released = expiryJob.releaseLapsedHolds();

		assertThat(released).isZero();
		assertThat(eventSeats.findById(seats.get(0).getId()).orElseThrow().getStatus())
				.isEqualTo(EventSeatStatus.SOLD);
		assertThat(bookings.findByReference(held.reference()).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.CONFIRMED);
	}

	@Test
	void runningTheJobTwiceReleasesNothingTheSecondTime() {
		hold();
		at(NOON.plus(Duration.ofMinutes(6)));

		assertThat(expiryJob.releaseLapsedHolds()).isEqualTo(1);
		assertThat(expiryJob.releaseLapsedHolds()).isZero();
	}

	private BookingView hold() {
		return bookingService.hold(request());
	}

	private BookingRequest request() {
		return new BookingRequest(
				event.getId(), List.of(seats.get(0).getId()), "Aman", "aman@example.com");
	}

	private void at(Instant moment) {
		given(clock.instant()).willReturn(moment);
	}

}
