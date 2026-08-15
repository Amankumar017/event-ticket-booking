package com.seatly.booking;

import com.seatly.common.NotFoundException;
import com.seatly.event.Event;
import com.seatly.event.EventSeat;
import com.seatly.event.EventSeatRepository;
import com.seatly.event.EventSeatStatus;
import com.seatly.support.IntegrationTest;
import com.seatly.support.SeatlyFixtures;
import com.seatly.support.TestAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Single-threaded behaviour of the hold-then-confirm flow.
 */
@Transactional
class BookingServiceTests extends IntegrationTest {

	@Autowired
	private BookingService bookingService;

	@Autowired
	private EventSeatRepository eventSeats;

	@Autowired
	private BookingRepository bookings;

	@Autowired
	private SeatlyFixtures fixtures;

	@Autowired
	private TestAccounts accounts;

	@BeforeEach
	void signIn() {
		accounts.actAs(accounts.customer());
	}

	@AfterEach
	void signOut() {
		accounts.signOut();
	}

	@Test
	void holdingSeatsStartsTheClockWithoutSellingAnything() {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);

		BookingView held = bookingService.hold(request(event, seats.get(0), seats.get(1)));

		assertThat(held.reference()).startsWith("SEAT-");
		assertThat(held.status()).isEqualTo(BookingStatus.PENDING);
		assertThat(held.expiresAt()).isNotNull();
		assertThat(held.confirmedAt()).isNull();
		assertThat(held.seats()).extracting(BookingView.BookedSeat::label)
				.containsExactlyInAnyOrder("A1", "A2");
	}

	@Test
	void heldSeatsCarryTheirDeadline() {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);

		BookingView held = bookingService.hold(request(event, seats.get(0)));

		EventSeat first = eventSeats.findById(seats.get(0).getId()).orElseThrow();
		assertThat(first.getStatus()).isEqualTo(EventSeatStatus.HELD);
		assertThat(first.getHeldUntil()).isEqualTo(held.expiresAt());
		assertThat(eventSeats.findById(seats.get(1).getId()).orElseThrow().getStatus())
				.isEqualTo(EventSeatStatus.AVAILABLE);
	}

	@Test
	void pricesTheBookingFromTheSeatsAndNotFromTheClient() {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);

		BookingView held = bookingService.hold(request(event, seats.get(0), seats.get(1)));

		assertThat(held.totalMinor()).isEqualTo(240_000L);
		assertThat(held.currency()).isEqualTo("INR");
	}

	@Test
	void refusesASeatSomebodyElseIsHolding() {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		bookingService.hold(request(event, seats.get(0)));

		assertThatThrownBy(() -> bookingService.hold(request(event, seats.get(0))))
				.isInstanceOf(SeatUnavailableException.class);
	}

	@Test
	void refusesToHoldWhenTheEventIsNotOnSale() {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		event.closeSales();

		assertThatThrownBy(() -> bookingService.hold(request(event, seats.get(0))))
				.isInstanceOf(SeatUnavailableException.class)
				.hasMessageContaining("not on sale");
	}

	@Test
	void refusesASeatThatDoesNotExist() {
		Event event = fixtures.onSaleEvent();

		assertThatThrownBy(() -> bookingService.hold(new BookingRequest(event.getId(), List.of(999_999L))))
				.isInstanceOf(NotFoundException.class);
	}

	@Test
	void confirmingTurnsTheHoldIntoASale() {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		BookingView held = bookingService.hold(request(event, seats.get(0)));

		BookingView confirmed = bookingService.confirmPaidBooking(held.reference(), Instant.now());

		assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(confirmed.confirmedAt()).isNotNull();
		assertThat(confirmed.expiresAt()).isNull();
		assertThat(eventSeats.findById(seats.get(0).getId()).orElseThrow().getStatus())
				.isEqualTo(EventSeatStatus.SOLD);
	}

	/** A webhook delivered twice must not be an error. */
	@Test
	void confirmingTwiceIsTheSameAsConfirmingOnce() {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		BookingView held = bookingService.hold(request(event, seats.get(0)));

		BookingView first = bookingService.confirmPaidBooking(held.reference(), Instant.now());
		BookingView second = bookingService.confirmPaidBooking(held.reference(), Instant.now());

		assertThat(second.status()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(second.confirmedAt()).isEqualTo(first.confirmedAt());
	}

	@Test
	void cancellingGivesTheSeatsBack() {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		BookingView held = bookingService.hold(request(event, seats.get(0)));

		BookingView cancelled = bookingService.cancel(held.reference());

		assertThat(cancelled.status()).isEqualTo(BookingStatus.CANCELLED);
		assertThat(eventSeats.findById(seats.get(0).getId()).orElseThrow().getStatus())
				.isEqualTo(EventSeatStatus.AVAILABLE);
	}

	/** The claim is kept as history, but it no longer holds the chair. */
	@Test
	void cancellingLeavesTheClaimBehindButNotActive() {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		BookingView held = bookingService.hold(request(event, seats.get(0)));
		bookingService.cancel(held.reference());

		Booking cancelled = bookings.findByReference(held.reference()).orElseThrow();

		assertThat(cancelled.getLines()).hasSize(1);
		assertThat(cancelled.getLines().get(0).isActive()).isFalse();
	}

	/** And the seat can be sold to somebody else afterwards. */
	@Test
	void aCancelledSeatCanBeHeldAgain() {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		BookingView first = bookingService.hold(request(event, seats.get(0)));
		bookingService.cancel(first.reference());

		BookingView second = bookingService.hold(request(event, seats.get(0)));

		assertThat(second.status()).isEqualTo(BookingStatus.PENDING);
		assertThat(second.reference()).isNotEqualTo(first.reference());
	}

	@Test
	void aConfirmedBookingCannotBeCancelledHere() {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		BookingView held = bookingService.hold(request(event, seats.get(0)));
		bookingService.confirmPaidBooking(held.reference(), Instant.now());

		assertThatThrownBy(() -> bookingService.cancel(held.reference()))
				.isInstanceOf(SeatUnavailableException.class);
	}

	@Test
	void findsABookingByItsReference() {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		BookingView held = bookingService.hold(request(event, seats.get(0)));

		assertThat(bookingService.byReference(held.reference()).seats()).hasSize(1);
	}

	private BookingRequest request(Event event, EventSeat... seats) {
		return new BookingRequest(event.getId(), List.of(seats).stream().map(EventSeat::getId).toList());
	}

}
