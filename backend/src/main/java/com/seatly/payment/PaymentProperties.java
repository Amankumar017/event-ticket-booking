package com.seatly.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param webhookSecret shared with the payment provider, used to sign callbacks
 */
@ConfigurationProperties(prefix = "seatly.payments")
public record PaymentProperties(String webhookSecret) {

	public PaymentProperties {
		if (webhookSecret == null || webhookSecret.isBlank()) {
			throw new IllegalArgumentException("seatly.payments.webhook-secret must be set");
		}
	}

}
