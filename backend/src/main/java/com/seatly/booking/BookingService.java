package com.seatly.booking;

import com.seatly.common.NotFoundException;
import com.seatly.event.Event;
import com.seatly.event.EventRepository;
import com.seatly.event.EventSeat;
import com.seatly.event.EventSeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Sells seats.
 *
 * <h2>This implementation is not safe under concurrency, on purpose</h2>
 *
 * Read the seats, check they are free, write them as sold. It is the obvious way
 * to write this, it passes every single-threaded test in
 * {@code BookingServiceTests}, and it is wrong.
 * <p>
 * The check and the write are two separate steps with nothing holding the seat
 * in between. Under PostgreSQL's default READ COMMITTED isolation, eight
 * requests for the same seat all run {@code isClaimableAt} before any of them
 * writes, and all eight conclude the seat is free.
 * <p>
 * What happens next depends on the order the statements reach the database --
 * which Hibernate decides, not this method. Both outcomes are measured in
 * {@code NaiveBookingUnderContentionTest}:
 * <ul>
 * <li><b>As written here</b>, Hibernate flushes the inserts before the update.
 * Inserting into {@code booking_seat} takes a foreign-key lock on the
 * {@code event_seat} row it points at; the update then needs an exclusive lock
 * on that same row, which every other transaction is also holding a
 * foreign-key lock on. They wait on each other and PostgreSQL kills all but one
 * with SQLSTATE 40P01. Seven of eight customers get an internal error.</li>
 * <li><b>With the seat update forced out first</b>, there is no deadlock: the
 * transactions queue politely on the row lock, each one re-reads after the
 * previous commits, and every one of them sells the same seat. Eight bookings,
 * one chair, no error anywhere.</li>
 * </ul>
 * The second is the worse bug, and it is the one that looks like nothing is
 * wrong. Stage 5 fixes the cause both share.
 */
@Service
public class BookingService {

	private final EventRepository events;
	private final EventSeatRepository eventSeats;
	private final BookingRepository bookings;
	private final BookingReferences references;
	private final Clock clock;

	public BookingService(EventRepository events, EventSeatRepository eventSeats,
			BookingRepository bookings, BookingReferences references, Clock clock) {
		this.events = events;
		this.eventSeats = eventSeats;
		this.bookings = bookings;
		this.references = references;
		this.clock = clock;
	}

	@Transactional
	public BookingView book(BookingRequest request) {
		Instant now = clock.instant();

		Event event = events.findById(request.eventId())
				.orElseThrow(() -> NotFoundException.of("Event", request.eventId()));
		if (!event.isOnSaleAt(now)) {
			throw new SeatUnavailableException("This event is not on sale");
		}

		List<EventSeat> seats = eventSeats.findAllById(request.eventSeatIds());
		if (seats.size() != request.eventSeatIds().size()) {
			throw new NotFoundException("One or more of those seats does not exist");
		}

		// ---- the check ----
		for (EventSeat seat : seats) {
			if (!seat.getEvent().getId().equals(event.getId())) {
				throw new SeatUnavailableException(
						"Seat " + seat.getSeat().label() + " does not belong to this event");
			}
			if (!seat.isClaimableAt(now)) {
				throw new SeatUnavailableException(
						"Seat " + seat.getSeat().label() + " is no longer available");
			}
		}

		// ---- and the write. Nothing holds the seats between the two. ----
		Booking booking = new Booking(
				references.next(), event, request.customerName(), request.customerEmail(), null);
		seats.forEach(booking::addSeat);
		// Sold outright for now: holds arrive in stage 6 and payment in stage 8.
		booking.confirm(now);
		bookings.save(booking);

		seats.forEach(EventSeat::markSold);
		eventSeats.saveAll(seats);

		return BookingView.of(booking);
	}

	@Transactional(readOnly = true)
	public BookingView byReference(String reference) {
		return bookings.findByReference(reference)
				.map(BookingView::of)
				.orElseThrow(() -> NotFoundException.of("Booking", reference));
	}

}
