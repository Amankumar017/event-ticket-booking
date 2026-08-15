package com.seatly.payment;

public enum PaymentStatus {

	/** Created, waiting for the customer and the provider. */
	REQUIRES_PAYMENT,

	/** The provider says the money moved. */
	SUCCEEDED,

	/** The provider says it did not. */
	FAILED

}
