package com.seatly.account;

/**
 * Wrong password, unknown address, or a disabled account.
 * <p>
 * One exception for all three, carrying no detail about which. The caller is
 * told the combination did not work and nothing more.
 */
public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("Email or password is incorrect");
	}

}
