package com.seatly.booking;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A request to hold specific seats.
 *
 * <h2>No customer details</h2>
 *
 * This used to carry a name and an email address, which meant the request said
 * who was buying -- and anybody could say anything. Identity now comes from the
 * verified access token, and this record says only what to buy.
 * <p>
 * Prices are absent for the same reason: what a seat costs is decided by the
 * server, every time, from the row it is selling.
 */
public record BookingRequest(

		@NotNull(message = "eventId is required")
		Long eventId,

		@NotEmpty(message = "pick at least one seat")
		@Size(max = 10, message = "at most 10 seats per booking")
		List<Long> eventSeatIds) {
}
