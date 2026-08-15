package com.seatly.booking;

import com.seatly.event.EventSeat;
import com.seatly.event.EventSeatRepository;
import com.seatly.event.EventSeatStatus;
import com.seatly.common.metrics.SeatlyMetrics;
import com.seatly.event.stream.SeatChanges;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Gives back the seats behind holds that ran out of time.
 *
 * <h2>The job is a tidy-up, not the rule</h2>
 *
 * A seat is free the moment its deadline passes, whether or not this job has
 * run. {@code EventSeat.isClaimableAt} decides that from the deadline itself, so
 * the seat map is right and a booking can be made against a lapsed hold even if
 * the job is stopped, slow, or has never run at all.
 * <p>
 * What the job does is make the stored state say the same thing: statuses back
 * to AVAILABLE, claims marked dead, bookings marked EXPIRED. Reports, indexes
 * and the audit query all read the stored state, so leaving it stale would be
 * its own kind of wrong; it just would not oversell anything.
 */
@Component
public class HoldExpiryJob {

	private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

	private final BookingRepository bookings;
	private final EventSeatRepository eventSeats;
	private final SeatHoldGuard holdGuard;
	private final SeatChanges seatChanges;
	private final SeatlyMetrics metrics;
	private final HoldProperties holdProperties;
	private final Clock clock;
	private final HoldExpiryJob self;

	public HoldExpiryJob(BookingRepository bookings, EventSeatRepository eventSeats,
			SeatHoldGuard holdGuard, SeatChanges seatChanges, SeatlyMetrics metrics,
			HoldProperties holdProperties, Clock clock,
			@Lazy HoldExpiryJob self) {
		this.bookings = bookings;
		this.eventSeats = eventSeats;
		this.holdGuard = holdGuard;
		this.seatChanges = seatChanges;
		this.metrics = metrics;
		this.holdProperties = holdProperties;
		this.clock = clock;
		this.self = self;
	}

	@Scheduled(fixedDelayString = "${seatly.hold.sweep-every}")
	public void sweep() {
		int released = releaseLapsedHolds();
		if (released > 0) {
			log.info("Released {} lapsed hold(s)", released);
		}
	}

	/**
	 * Releases one batch of lapsed holds, each in its own transaction.
	 *
	 * @return how many bookings were expired
	 */
	public int releaseLapsedHolds() {
		Instant now = clock.instant();
		List<Long> lapsed = bookings.findLapsedIds(now, Limit.of(holdProperties.batchSize()));

		int released = 0;
		for (Long bookingId : lapsed) {
			// Through the proxy, so each booking really does get its own
			// transaction. Calling releaseOne(id) directly would run inside this
			// method's caller instead: the classic self-invocation trap, where
			// @Transactional silently does nothing.
			if (self.releaseOne(bookingId, now)) {
				released++;
			}
		}
		return released;
	}

	/**
	 * Expires a single booking and hands its seats back.
	 * <p>
	 * Seats are locked first, in id order, exactly as the booking path does --
	 * and the booking's state is re-read under that lock, because between finding
	 * it and locking its seats the customer may well have paid.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean releaseOne(Long bookingId, Instant now) {
		Booking booking = bookings.findById(bookingId).orElse(null);
		if (booking == null) {
			return false;
		}

		List<Long> seatIds = booking.getLines().stream()
				.map(line -> line.getEventSeat().getId())
				.sorted()
				.toList();
		List<EventSeat> seats = eventSeats.lockAllById(seatIds);

		// Re-check under the lock. The hold may have been confirmed or cancelled
		// while this job was deciding to touch it.
		if (booking.getStatus() != BookingStatus.PENDING || !booking.hasLapsedBy(now)) {
			return false;
		}

		booking.expire();
		booking.getLines().forEach(BookingSeat::releaseClaim);
		List<EventSeat> released = seats.stream()
				.filter(seat -> seat.getStatus() == EventSeatStatus.HELD)
				.toList();
		released.forEach(EventSeat::release);
		holdGuard.releaseAll(seatIds);
		seatChanges.announce(released);
		metrics.seatsReleased(released.size(), "expired");

		return true;
	}

}
