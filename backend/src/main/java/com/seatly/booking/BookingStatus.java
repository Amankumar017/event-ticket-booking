package com.seatly.booking;

public enum BookingStatus {

	/** Seats are held; payment has not completed. */
	PENDING,

	/** Paid. The seats are sold. */
	CONFIRMED,

	/** The hold lapsed before payment arrived. */
	EXPIRED,

	/** Abandoned by the customer, or called off. */
	CANCELLED

}
