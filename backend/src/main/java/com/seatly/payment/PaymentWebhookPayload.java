package com.seatly.payment;

import jakarta.validation.constraints.NotBlank;

/**
 * What the provider sends.
 *
 * @param eventId          the provider's id for this delivery, and the thing
 *                         that makes repeats detectable
 * @param type             {@code payment.succeeded} or {@code payment.failed}
 * @param paymentReference which payment it is about
 * @param reason           why it failed, when it did
 */
public record PaymentWebhookPayload(
		@NotBlank String eventId,
		@NotBlank String type,
		@NotBlank String paymentReference,
		String reason) {
}
