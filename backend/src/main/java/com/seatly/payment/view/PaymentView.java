package com.seatly.payment.view;

import com.seatly.payment.Payment;
import com.seatly.payment.PaymentStatus;

import java.time.Instant;

public record PaymentView(
		String paymentReference,
		String bookingReference,
		long amountMinor,
		String currency,
		PaymentStatus status,
		String failureReason,
		Instant settledAt) {

	public static PaymentView of(Payment payment) {
		return new PaymentView(
				payment.getProviderReference(),
				payment.getBooking().getReference(),
				payment.getAmountMinor(),
				payment.getCurrency(),
				payment.getStatus(),
				payment.getFailureReason(),
				payment.getSettledAt());
	}

}
