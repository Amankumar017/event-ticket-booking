package com.seatly.booking;

import com.seatly.common.NotFoundException;
import com.seatly.event.Event;
import com.seatly.event.EventRepository;
import com.seatly.event.EventSeat;
import com.seatly.event.EventSeatRepository;
import com.seatly.event.EventSeatStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Holds seats, and turns holds into sales.
 *
 * <h2>Why the lock comes before the check</h2>
 *
 * An earlier version read the seats, checked they were free, and then wrote
 * them. It passed every single-threaded test and failed two different ways
 * under load -- deadlocking seven callers out of eight, or, with the writes
 * reordered, quietly selling one chair to all eight. Both measurements are in
 * {@code docs/concurrency.md}.
 * <p>
 * Both failures had the same cause: a decision made from a row that nothing was
 * holding. So the first thing this class does with a seat is take an exclusive
 * lock on it, and only then ask whether it is free.
 *
 * <h2>Lock order</h2>
 *
 * Every method here locks {@code event_seat} rows first, in ascending id order,
 * before touching the booking behind them. The expiry job follows the same rule.
 * One order, agreed everywhere, is what stops two paths from waiting on each
 * other's locks.
 */
@Service
public class BookingService {

	private final EventRepository events;
	private final EventSeatRepository eventSeats;
	private final BookingRepository bookings;
	private final BookingSeatRepository bookingSeats;
	private final BookingReferences references;
	private final SeatHoldGuard holdGuard;
	private final HoldProperties holdProperties;
	private final Clock clock;

	public BookingService(EventRepository events, EventSeatRepository eventSeats,
			BookingRepository bookings, BookingSeatRepository bookingSeats,
			BookingReferences references, SeatHoldGuard holdGuard,
			HoldProperties holdProperties, Clock clock) {
		this.events = events;
		this.eventSeats = eventSeats;
		this.bookings = bookings;
		this.bookingSeats = bookingSeats;
		this.references = references;
		this.holdGuard = holdGuard;
		this.holdProperties = holdProperties;
		this.clock = clock;
	}

	/**
	 * Holds seats for this customer until the deadline, and no longer.
	 * <p>
	 * The result is a PENDING booking. Nothing has been paid for and nothing is
	 * sold: if {@link #confirm} does not arrive before {@code expiresAt}, the
	 * expiry job gives the chairs back.
	 */
	@Transactional
	public BookingView hold(BookingRequest request) {
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

		// Cheap rejection before any transaction work. Only ever a "no" -- a
		// "yes" here means nothing until the database agrees below.
		if (!holdGuard.tryClaimAll(seatIds, holdProperties.ttl())) {
			throw new SeatUnavailableException("Somebody else is holding one of those seats");
		}
		releaseGuardIfThisTransactionFails(seatIds);

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

		retireClaimsLeftByLapsedHolds(seatIds, now);

		// ---- and the write, under the lock taken above ----
		Instant deadline = now.plus(holdProperties.ttl());
		Booking booking = new Booking(
				references.next(), event, request.customerName(), request.customerEmail(), deadline);
		seats.forEach(booking::addSeat);
		bookings.save(booking);
		seats.forEach(seat -> seat.holdUntil(deadline));

		return BookingView.of(booking);
	}

	/**
	 * Turns a live hold into a sale.
	 * <p>
	 * Payment arrives in stage 8; for now confirming is the customer saying yes.
	 * A hold that has already lapsed is refused rather than quietly revived --
	 * by then the chairs may belong to somebody else.
	 */
	@Transactional
	public BookingView confirm(String reference) {
		Instant now = clock.instant();
		Booking booking = lockSeatsThenLoad(reference);

		if (booking.getStatus() == BookingStatus.CONFIRMED) {
			return BookingView.of(booking);
		}
		if (booking.getStatus() != BookingStatus.PENDING) {
			throw new SeatUnavailableException(
					"This booking is " + booking.getStatus().name().toLowerCase());
		}
		if (booking.hasLapsedBy(now)) {
			throw new SeatUnavailableException("This hold has expired");
		}

		booking.confirm(now);
		booking.getLines().forEach(line -> line.getEventSeat().markSold());
		holdGuard.releaseAll(seatIdsOf(booking));

		return BookingView.of(booking);
	}

	/** Gives the seats back before the deadline. */
	@Transactional
	public BookingView cancel(String reference) {
		Booking booking = lockSeatsThenLoad(reference);

		if (booking.getStatus() == BookingStatus.CONFIRMED) {
			throw new SeatUnavailableException("A confirmed booking cannot be cancelled here");
		}
		if (booking.getStatus() == BookingStatus.PENDING) {
			releaseSeatsOf(booking);
			booking.cancel();
			holdGuard.releaseAll(seatIdsOf(booking));
		}

		return BookingView.of(booking);
	}

	@Transactional(readOnly = true)
	public BookingView byReference(String reference) {
		return bookings.findByReference(reference)
				.map(BookingView::of)
				.orElseThrow(() -> NotFoundException.of("Booking", reference));
	}

	/**
	 * Retires the claim a lapsed hold left on these seats.
	 * <p>
	 * A seat is free the moment its deadline passes, but the {@code booking_seat}
	 * row that claimed it is still marked live until something says otherwise. The
	 * partial unique index counts that stale row, so without this the next
	 * customer's insert is rejected for a seat that is genuinely available. The
	 * expiry job would get to it eventually; nobody should have to wait for that.
	 * <p>
	 * Whoever takes the seat tidies up what they are superseding. The seats are
	 * already locked, so no other transaction can be doing the same thing.
	 */
	private void retireClaimsLeftByLapsedHolds(List<Long> seatIds, Instant now) {
		List<BookingSeat> live = bookingSeats.findLiveClaims(seatIds);
		if (live.isEmpty()) {
			return;
		}

		for (BookingSeat claim : live) {
			Booking behindIt = claim.getBooking();
			if (!behindIt.hasLapsedBy(now)) {
				// The seat passed the availability check, so any live claim on it
				// should have lapsed. If one has not, the two disagree and the
				// safe answer is to refuse rather than to overwrite.
				throw new SeatUnavailableException(
						"Seat " + claim.getEventSeat().getSeat().label() + " is no longer available");
			}
			claim.releaseClaim();
			behindIt.expire();
		}

		// Explicitly, and this matters: Hibernate flushes inserts before updates.
		// Left to itself it would send the new claim first and have the database
		// reject it against the very row being retired here. The same ordering
		// rule that deadlocked the unlocked booking path in stage 4.
		bookingSeats.flush();
	}

	/**
	 * Loads a booking with its seats already locked, in the agreed order.
	 * <p>
	 * The booking is read twice on purpose: once unlocked to find out which seats
	 * it holds, then again after those rows are locked, so the state it is acted
	 * on is the state that exists under the lock.
	 */
	private Booking lockSeatsThenLoad(String reference) {
		Booking unlocked = bookings.findByReference(reference)
				.orElseThrow(() -> NotFoundException.of("Booking", reference));

		List<Long> seatIds = seatIdsOf(unlocked);
		eventSeats.lockAllById(seatIds);

		return bookings.findByReference(reference)
				.orElseThrow(() -> NotFoundException.of("Booking", reference));
	}

	private void releaseSeatsOf(Booking booking) {
		booking.getLines().forEach(line -> {
			if (line.getEventSeat().getStatus() == EventSeatStatus.HELD) {
				line.getEventSeat().release();
			}
			line.releaseClaim();
		});
	}

	private List<Long> seatIdsOf(Booking booking) {
		return booking.getLines().stream()
				.map(line -> line.getEventSeat().getId())
				.sorted()
				.toList();
	}

	/**
	 * Hands the guard keys back if this transaction does not commit.
	 * <p>
	 * Without it, a caller who claims the guard and then loses on the database
	 * check would leave the seat looking taken for the whole TTL. Inconvenient
	 * rather than incorrect -- the database would still let the next caller
	 * through -- but there is no reason to make people wait for nothing.
	 */
	private void releaseGuardIfThisTransactionFails(List<Long> seatIds) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != TransactionSynchronization.STATUS_COMMITTED) {
					holdGuard.releaseAll(seatIds);
				}
			}
		});
	}

}
