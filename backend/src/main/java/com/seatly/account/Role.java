package com.seatly.account;

public enum Role {

	/** Buys tickets. What a self-registered account gets. */
	CUSTOMER,

	/** Runs events, and may see who has booked into them. */
	ORGANIZER,

	/** Everything. */
	ADMIN;

	/**
	 * The authority name Spring Security expects.
	 * <p>
	 * The {@code ROLE_} prefix is not decoration: {@code hasRole("ORGANIZER")}
	 * looks for an authority called {@code ROLE_ORGANIZER}, and a mismatch here
	 * fails as a silent 403 rather than as an error anybody can find.
	 */
	public String authority() {
		return "ROLE_" + name();
	}

}
