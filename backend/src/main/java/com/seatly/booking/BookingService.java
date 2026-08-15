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
 * <h2>Why the lock comes before the check</h2>
 *
 * An earlier version of this class read the seats, checked they were free, and
 * then wrote them as sold. It passed every single-threaded test and failed two
 * different ways under load -- deadlocking seven callers out of eight, or, with
 * the writes reordered, quietly selling one chair to all eight. Both
 * measurements are in {@code docs/concurrency.md}.
 * <p>
 * Both failures had the same cause: a decision made from a row that nothing was
 * holding. So the first thing this method does with a seat is take an exclusive
 * lock on it, and only then ask whether it is free. A competing transaction
 * blocks on that lock, and by the time it is let through the seat is already
 * sold -- which it sees, and reports honestly.
 * <p>
 * Optimistic locking would also prevent the double sale, and
 * {@code BookingUnderContentionTest} measures it doing so. It is the wrong tool
 * here: on a single seat that many people want at once, every loser does its
 * work and throws it away. Pessimistic locking makes them wait instead, which
 * costs less and gives a clearer answer. The {@code @Version} column stays as a
 * safety net for write paths that do not lock.
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

		// Sorted and de-duplicated: sorted so that overlapping bookings always
		// take their locks in the same order, de-duplicated so that asking for
		// the same seat twice cannot claim it twice.
		List<Long> seatIds = request.eventSeatIds().stream().distinct().sorted().toList();

		// ---- the lock, before anything is decided ----
		List<EventSeat> seats = eventSeats.lockAllById(seatIds);
		if (seats.size() != seatIds.size()) {
			throw new NotFoundException("One or more of those seats does not exist");
		}

		// ---- the check, now that nobody else can be reading these rows ----
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

		// ---- and the write, under the lock taken above ----
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
