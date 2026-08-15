package com.seatly.account;

public class EmailAlreadyRegisteredException extends RuntimeException {

	public EmailAlreadyRegisteredException() {
		super("That email address is already registered");
	}

}
