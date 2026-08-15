package com.seatly.common.idempotency;

/**
 * The key has been seen before, with a different request.
 * <p>
 * Refused rather than answered. Replaying the earlier reply would tell the
 * client its new request succeeded when nothing of the sort happened, and doing
 * the new work under an old key would defeat the point of the key entirely.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

	public IdempotencyKeyReusedException() {
		super("That idempotency key was already used for a different request");
	}

}
