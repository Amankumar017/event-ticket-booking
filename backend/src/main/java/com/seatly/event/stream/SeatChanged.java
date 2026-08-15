package com.seatly.event.stream;

import com.seatly.event.EventSeat;
import com.seatly.event.EventSeatStatus;

import java.time.Instant;

/**
 * One seat has changed hands.
 *
 * @param eventId     which event's chart it belongs to, so listeners can filter
 * @param eventSeatId the seat
 * @param status      what it is now
 * @param heldUntil   when a hold lapses, if it is held
 */
public record SeatChanged(Long eventId, Long eventSeatId, EventSeatStatus status, Instant heldUntil) {

	public static SeatChanged of(EventSeat seat) {
		return new SeatChanged(
				seat.getEvent().getId(), seat.getId(), seat.getStatus(), seat.getHeldUntil());
	}

}
