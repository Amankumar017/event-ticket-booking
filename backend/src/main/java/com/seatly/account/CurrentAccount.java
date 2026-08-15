package com.seatly.account;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Who is making this request, according to the verified token.
 *
 * <h2>Identity comes from here, never from the request body</h2>
 *
 * A booking used to carry the customer's name and email in its payload, which
 * meant anyone could book as anyone. The body now says what to buy; this says
 * who is buying.
 */
@Component
public class CurrentAccount {

	/** The signed-in account's id. */
	public Long id() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof Jwt token)) {
			throw new NotSignedInException();
		}
		return Long.valueOf(token.getSubject());
	}

	public boolean isSignedIn() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication != null && authentication.getPrincipal() instanceof Jwt;
	}

	public static class NotSignedInException extends RuntimeException {
		public NotSignedInException() {
			super("You need to be signed in to do that");
		}
	}

}
