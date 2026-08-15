package com.seatly.booking;

import com.seatly.account.AppUser;
import com.seatly.account.AppUserRepository;
import com.seatly.account.CurrentAccount;
import com.seatly.common.NotFoundException;
import com.seatly.common.metrics.SeatlyMetrics;
import com.seatly.common.outbox.OutboxMessage;
import com.seatly.common.outbox.OutboxMessageRepository;
import com.seatly.event.Event;
import com.seatly.event.EventRepository;
import com.seatly.event.EventSeat;
import com.seatly.event.EventSeatRepository;
import com.seatly.event.EventSeatStatus;
import com.seatly.event.stream.SeatChanges;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

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
	private final OutboxMessageRepository outbox;
	private final BookingReferences references;
	private final AppUserRepository users;
	private final CurrentAccount currentAccount;
	private final SeatChanges seatChanges;
	private final SeatlyMetrics metrics;
	private final SeatHoldGuard holdGuard;
	private final HoldProperties holdProperties;
	private final Clock clock;
	private final BookingService self;

	public BookingService(EventRepository events, EventSeatRepository eventSeats,
			BookingRepository bookings, BookingSeatRepository bookingSeats, OutboxMessageRepository outbox,
			BookingReferences references, AppUserRepository users, CurrentAccount currentAccount,
			SeatChanges seatChanges, SeatlyMetrics metrics, SeatHoldGuard holdGuard,
			HoldProperties holdProperties, Clock clock, @Lazy BookingService self) {
		this.events = events;
		this.eventSeats = eventSeats;
		this.bookings = bookings;
		this.bookingSeats = bookingSeats;
		this.outbox = outbox;
		this.references = references;
		this.users = users;
		this.currentAccount = currentAccount;
		this.seatChanges = seatChanges;
		this.metrics = metrics;
		this.holdGuard = holdGuard;
		this.holdProperties = holdProperties;
		this.clock = clock;
		this.self = self;
	}

	/**
	 * Holds seats for this customer until the deadline, and no longer.
	 * <p>
	 * The result is a PENDING booking. Nothing has been paid for and nothing is
	 * sold: if payment does not arrive before {@code expiresAt}, the expiry job
	 * gives the chairs back.
	 */
	public BookingView hold(BookingRequest request) {
		long startedAt = System.nanoTime();
		try {
			BookingView held = self.holdSeats(request);
			metrics.holdAttempt(SeatlyMetrics.HoldOutcome.GRANTED, System.nanoTime() - startedAt);
			return held;
		}
		catch (SeatUnavailableException refused) {
			metrics.holdAttempt(SeatlyMetrics.HoldOutcome.REFUSED, System.nanoTime() - startedAt);
			throw refused;
		}
		catch (RuntimeException failure) {
			metrics.holdAttempt(SeatlyMetrics.HoldOutcome.FAILED, System.nanoTime() - startedAt);
			throw failure;
		}
	}

	@Transactional
	public BookingView holdSeats(BookingRequest request) {
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
		Booking booking = new Booking(references.next(), event, buyer(), deadline);
		seats.forEach(booking::addSeat);
		bookings.save(booking);
		seats.forEach(seat -> seat.holdUntil(deadline));
		seatChanges.announce(seats);

		return BookingView.of(booking);
	}

	/**
	 * Confirms a booking because it has been paid for.
	 * <p>
	 * There is no customer-facing way to confirm a booking any more: money is the
	 * only thing that turns a hold into a sale, and this is called by the webhook
	 * that reports it. Since there is no signed-in account behind a webhook there
	 * is no ownership check here, which is why this is a separate method rather
	 * than a flag on one -- a boolean that switches off an authorisation check is
	 * the kind of thing that ends up being passed from somewhere it should not
	 * be.
	 * <p>
	 * A lapsed deadline is not by itself a reason to refuse. If the seats were
	 * still this booking's when the money arrived, nobody was waiting for them
	 * and the customer should have their tickets. If somebody else had taken
	 * them, that act retired this booking's claim and expired it, which is the
	 * case caught below.
	 */
	@Transactional
	public BookingView confirmPaidBooking(String reference, Instant paidAt) {
		Booking booking = lockSeatsThenLoad(reference);

		if (booking.getStatus() == BookingStatus.CONFIRMED) {
			return BookingView.of(booking);
		}
		if (booking.getStatus() != BookingStatus.PENDING) {
			// Money arrived for a booking that has expired or been cancelled. The
			// seats are gone; this needs a refund, not a confirmation.
			throw new PaymentArrivedTooLateException(reference, booking.getStatus());
		}

		booking.confirm(paidAt);
		booking.getLines().forEach(line -> line.getEventSeat().markSold());
		holdGuard.releaseAll(seatIdsOf(booking));
		seatChanges.announce(seatsOf(booking));
		metrics.seatsSold(booking.getLines().size());
		announceConfirmation(booking);

		return BookingView.of(booking);
	}

	/** Gives the seats back before the deadline. */
	@Transactional
	public BookingView cancel(String reference) {
		Booking booking = lockSeatsThenLoad(reference);
		mustBelongToCaller(booking);

		if (booking.getStatus() == BookingStatus.CONFIRMED) {
			throw new SeatUnavailableException("A confirmed booking cannot be cancelled here");
		}
		if (booking.getStatus() == BookingStatus.PENDING) {
			releaseSeatsOf(booking);
			booking.cancel();
			holdGuard.releaseAll(seatIdsOf(booking));
			seatChanges.announce(seatsOf(booking));
			metrics.seatsReleased(booking.getLines().size(), "cancelled");
		}

		return BookingView.of(booking);
	}

	@Transactional(readOnly = true)
	public BookingView byReference(String reference) {
		Booking booking = bookings.findByReference(reference)
				.orElseThrow(() -> NotFoundException.of("Booking", reference));
		mustBelongToCaller(booking);
		return BookingView.of(booking);
	}

	/** Everything the signed-in customer has booked, newest first. */
	@Transactional(readOnly = true)
	public List<BookingView> mine() {
		return bookings.findByUserIdOrderByIdDesc(currentAccount.id()).stream()
				.map(BookingView::of)
				.toList();
	}

	/**
	 * Every booking for an event, for the people who run it.
	 * <p>
	 * No ownership check here, deliberately -- an organiser is meant to see other
	 * people's bookings. The role check on the endpoint is what stands between
	 * this and a customer reading the whole guest list.
	 */
	@Transactional(readOnly = true)
	public List<BookingView> forEvent(Long eventId) {
		return bookings.findByEventIdOrderByIdAsc(eventId).stream()
				.map(BookingView::of)
				.toList();
	}

	/**
	 * Writes the confirmation email into the outbox, inside this transaction.
	 * <p>
	 * Not sent here. If this transaction rolls back the message goes with it, and
	 * a customer is never told about a booking that does not exist.
	 */
	private void announceConfirmation(Booking booking) {
		String seats = booking.getLines().stream()
				.map(line -> line.getEventSeat().getSeat().label())
				.collect(Collectors.joining(", "));

		outbox.save(new OutboxMessage(
				"booking.confirmed",
				booking.getCustomerEmail(),
				"Booking %s confirmed for %s: %s".formatted(
						booking.getReference(), booking.getEvent().getTitle(), seats)));
	}

	private AppUser buyer() {
		Long id = currentAccount.id();
		return users.findById(id).orElseThrow(() -> NotFoundException.of("Account", id));
	}

	/**
	 * Refuses a booking that belongs to somebody else.
	 * <p>
	 * Reported as "not found" rather than "forbidden" on purpose. A 403 confirms
	 * that the reference exists, which turns this endpoint into a way of testing
	 * guesses; a 404 tells someone who is not the owner exactly as much as a
	 * reference that was never issued.
	 */
	private void mustBelongToCaller(Booking booking) {
		if (!booking.belongsTo(currentAccount.id())) {
			throw NotFoundException.of("Booking", booking.getReference());
		}
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

	private List<EventSeat> seatsOf(Booking booking) {
		return booking.getLines().stream().map(BookingSeat::getEventSeat).toList();
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
