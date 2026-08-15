package com.seatly.event.view;

import java.time.Instant;

/**
 * One line in the "what's on" list.
 */
public record EventSummary(
		Long id,
		String title,
		String venueName,
		String city,
		Instant startsAt,
		Instant salesCloseAt,
		long availableSeats) {
}
