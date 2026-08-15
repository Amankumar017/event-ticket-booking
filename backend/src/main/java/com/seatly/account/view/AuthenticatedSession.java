package com.seatly.account.view;

/**
 * The result of signing in or refreshing.
 * <p>
 * {@code refreshToken} never reaches the response body: the controller moves it
 * into an http-only cookie and returns the rest. It travels in this record only
 * because the service has to hand it somewhere.
 */
public record AuthenticatedSession(
		String accessToken,
		long expiresInSeconds,
		String refreshToken,
		CurrentUser user) {
}
