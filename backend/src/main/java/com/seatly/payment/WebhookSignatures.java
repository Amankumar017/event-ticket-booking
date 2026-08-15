package com.seatly.payment;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/**
 * Signs and verifies webhook payloads.
 *
 * <h2>Why a webhook needs a signature at all</h2>
 *
 * The endpoint is a public URL that turns unpaid bookings into paid ones. Without
 * a signature, anybody who guesses its shape can confirm their own booking for
 * free. The provider signs each delivery with a shared secret, and a body that
 * does not carry a matching signature is not from the provider.
 */
@Component
public class WebhookSignatures {

	private static final String ALGORITHM = "HmacSHA256";

	private final PaymentProperties properties;

	public WebhookSignatures(PaymentProperties properties) {
		this.properties = properties;
	}

	public String sign(String payload) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(
					properties.webhookSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
			byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));

			StringBuilder hex = new StringBuilder(signature.length * 2);
			for (byte b : signature) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		}
		catch (GeneralSecurityException impossible) {
			throw new IllegalStateException("HMAC-SHA256 is unavailable", impossible);
		}
	}

	/**
	 * Whether this signature belongs to this payload.
	 * <p>
	 * The comparison is {@link MessageDigest#isEqual}, not {@code equals}. A
	 * normal string comparison stops at the first differing character, and the
	 * time it takes leaks how much of a guess was right -- enough, over many
	 * attempts, to reconstruct a valid signature one character at a time.
	 */
	public boolean isValid(String payload, String presentedSignature) {
		if (presentedSignature == null || presentedSignature.isBlank()) {
			return false;
		}
		return MessageDigest.isEqual(
				sign(payload).getBytes(StandardCharsets.UTF_8),
				presentedSignature.getBytes(StandardCharsets.UTF_8));
	}

}
