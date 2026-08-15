package com.seatly.booking;

/**
 * Thrown when seats cannot be claimed: already sold, held by somebody else, or
 * belonging to an event that is not on sale.
 */
public class SeatUnavailableException extends RuntimeException {

	public SeatUnavailableException(String message) {
		super(message);
	}

}
