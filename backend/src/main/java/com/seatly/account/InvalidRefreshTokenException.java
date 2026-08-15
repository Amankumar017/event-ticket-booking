package com.seatly.account;

/**
 * The presented refresh token was unknown, expired, or already spent.
 * <p>
 * As with sign-in, the three are indistinguishable to the caller. A client that
 * sees this signs in again; there is nothing else useful it could do with a
 * finer answer.
 */
public class InvalidRefreshTokenException extends RuntimeException {

	public InvalidRefreshTokenException() {
		super("Your session has ended. Please sign in again.");
	}

}
