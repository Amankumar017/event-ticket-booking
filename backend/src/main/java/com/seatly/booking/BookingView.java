package com.seatly.booking;

import java.time.Instant;
import java.util.List;

public record BookingView(
		String reference,
		Long eventId,
		BookingStatus status,
		long totalMinor,
		String currency,
		/** When the hold lapses. Null once the booking is no longer pending. */
		Instant expiresAt,
		Instant confirmedAt,
		List<BookedSeat> seats) {

	public record BookedSeat(Long eventSeatId, String label, long priceMinor) {
	}

	static BookingView of(Booking booking) {
		List<BookedSeat> seats = booking.getLines().stream()
				.map(line -> new BookedSeat(
						line.getEventSeat().getId(),
						line.getEventSeat().getSeat().label(),
						line.getPriceMinor()))
				.toList();

		return new BookingView(
				booking.getReference(),
				booking.getEvent().getId(),
				booking.getStatus(),
				booking.getTotalMinor(),
				booking.getCurrency(),
				booking.getExpiresAt(),
				booking.getConfirmedAt(),
				seats);
	}

}
