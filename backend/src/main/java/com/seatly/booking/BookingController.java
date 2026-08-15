package com.seatly.booking;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

	private final BookingService bookings;

	public BookingController(BookingService bookings) {
		this.bookings = bookings;
	}

	/** Holds the seats and starts the clock. Nothing is sold yet. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public BookingView hold(@Valid @RequestBody BookingRequest request) {
		return bookings.hold(request);
	}

	/**
	 * Turns a live hold into a sale.
	 * <p>
	 * Idempotent: confirming an already confirmed booking returns it unchanged
	 * rather than failing, because a customer who double-clicks, or a client that
	 * retries a timed-out request, should not be punished for it.
	 */
	@PostMapping("/{reference}/confirmation")
	public BookingView confirm(@PathVariable String reference) {
		return bookings.confirm(reference);
	}

	/** Gives the seats back before the deadline. */
	@PostMapping("/{reference}/cancellation")
	public BookingView cancel(@PathVariable String reference) {
		return bookings.cancel(reference);
	}

	@GetMapping("/{reference}")
	public BookingView byReference(@PathVariable String reference) {
		return bookings.byReference(reference);
	}

}
