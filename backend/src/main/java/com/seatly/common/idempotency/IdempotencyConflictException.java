package com.seatly.common.idempotency;

/**
 * The same key is already in flight.
 * <p>
 * Answered as 409 so the client retries in a moment rather than starting a
 * second attempt at the same work.
 */
public class IdempotencyConflictException extends RuntimeException {

	public IdempotencyConflictException(String message) {
		super(message);
	}

}
