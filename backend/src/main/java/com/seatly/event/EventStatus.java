package com.seatly.event;

public enum EventStatus {

	/** Being set up; not visible to customers. */
	DRAFT,

	/** Open for booking. */
	ON_SALE,

	/** Sales window has ended. */
	CLOSED,

	/** Called off; existing bookings are refundable. */
	CANCELLED

}
