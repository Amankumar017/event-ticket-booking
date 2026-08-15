package com.seatly.booking;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A request to buy specific seats.
 * <p>
 * The client names the seats it wants by id -- the ones it was shown on the seat
 * map. It does not send prices: what a seat costs is decided by the server,
 * every time, from the row it is selling.
 */
public record BookingRequest(

		@NotNull(message = "eventId is required")
		Long eventId,

		@NotEmpty(message = "pick at least one seat")
		@Size(max = 10, message = "at most 10 seats per booking")
		List<Long> eventSeatIds,

		@NotBlank(message = "customerName is required")
		@Size(max = 120)
		String customerName,

		@NotBlank(message = "customerEmail is required")
		@Email(message = "customerEmail must be an email address")
		@Size(max = 160)
		String customerEmail) {
}
