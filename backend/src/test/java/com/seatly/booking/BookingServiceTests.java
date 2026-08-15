package com.seatly.booking;

import com.seatly.common.NotFoundException;
import com.seatly.event.Event;
import com.seatly.event.EventSeat;
import com.seatly.event.EventSeatRepository;
import com.seatly.event.EventSeatStatus;
import com.seatly.support.IntegrationTest;
import com.seatly.support.SeatlyFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Single-threaded behaviour of the booking service.
 * <p>
 * Everything here passes. That is the point worth sitting with: a suite like
 * this is what most implementations are shipped on, and it says nothing at all
 * about what happens when two customers arrive at once.
 */
@Transactional
class BookingServiceTests extends IntegrationTest {

	@Autowired
	private BookingService bookingService;

	@Autowired
	private EventSeatRepository eventSeats;

	@Autowired
	private SeatlyFixtures fixtures;

	@Test
	void sellsTheRequestedSeats() {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);

		BookingView booking = bookingService.book(request(event, seats.get(0), seats.get(1)));

		assertThat(booking.reference()).startsWith("SEAT-");
		assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(booking.seats()).extracting(BookingView.BookedSeat::label)
				.containsExactlyInAnyOrder("A1", "A2");
	}

	@Test
	void marksTheSeatsSold() {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);

		bookingService.book(request(event, seats.get(0)));

		assertThat(eventSeats.findById(seats.get(0).getId()).orElseThrow().getStatus())
				.isEqualTo(EventSeatStatus.SOLD);
		assertThat(eventSeats.findById(seats.get(1).getId()).orElseThrow().getStatus())
				.isEqualTo(EventSeatStatus.AVAILABLE);
	}

	@Test
	void pricesTheBookingFromTheSeatsAndNotFromTheClient() {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);

		BookingView booking = bookingService.book(request(event, seats.get(0), seats.get(1)));

		assertThat(booking.totalMinor()).isEqualTo(240_000L);
		assertThat(booking.currency()).isEqualTo("INR");
	}

	/** Sequentially, the check does exactly what it looks like it does. */
	@Test
	void refusesASeatThatHasAlreadyBeenSold() {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		bookingService.book(request(event, seats.get(0)));

		assertThatThrownBy(() -> bookingService.book(request(event, seats.get(0))))
				.isInstanceOf(SeatUnavailableException.class)
				.hasMessageContaining("A1");
	}

	@Test
	void refusesToSellWhenTheEventIsNotOnSale() {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		event.closeSales();

		assertThatThrownBy(() -> bookingService.book(request(event, seats.get(0))))
				.isInstanceOf(SeatUnavailableException.class)
				.hasMessageContaining("not on sale");
	}

	@Test
	void refusesASeatThatDoesNotExist() {
		Event event = fixtures.onSaleEvent();

		assertThatThrownBy(() -> bookingService.book(new BookingRequest(
				event.getId(), List.of(999_999L), "Aman", "aman@example.com")))
				.isInstanceOf(NotFoundException.class);
	}

	@Test
	void findsABookingByItsReference() {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		BookingView booked = bookingService.book(request(event, seats.get(0)));

		assertThat(bookingService.byReference(booked.reference()).seats()).hasSize(1);
	}

	private BookingRequest request(Event event, EventSeat... seats) {
		return new BookingRequest(
				event.getId(),
				List.of(seats).stream().map(EventSeat::getId).toList(),
				"Aman",
				"aman@example.com");
	}

}
