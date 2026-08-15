package com.seatly.event;

public enum EventSeatStatus {

	/** Nobody has claimed it. */
	AVAILABLE,

	/** Reserved for one customer until {@code heldUntil} passes. */
	HELD,

	/** Paid for. Terminal, short of a refund. */
	SOLD,

	/** Withheld from sale: restricted view, production use, damage. */
	BLOCKED

}
