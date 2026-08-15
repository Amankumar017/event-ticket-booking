package com.seatly.booking;

/**
 * Money arrived for a booking that is no longer holding its seats.
 * <p>
 * The hold expired or was cancelled, and by now the chairs may belong to
 * somebody else. Confirming anyway would oversell them, so the payment stands
 * and the booking does not: what this needs is a refund, which is why it is a
 * distinct exception rather than a generic failure.
 */
public class PaymentArrivedTooLateException extends RuntimeException {

	private final String bookingReference;

	public PaymentArrivedTooLateException(String bookingReference, BookingStatus status) {
		super("Payment arrived for booking %s, which is %s"
				.formatted(bookingReference, status.name().toLowerCase()));
		this.bookingReference = bookingReference;
	}

	public String getBookingReference() {
		return bookingReference;
	}

}
