package com.seatly.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/payments/webhook")
public class PaymentWebhookController {

	private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

	static final String SIGNATURE_HEADER = "X-Seatly-Signature";

	private final PaymentWebhookService webhooks;
	private final WebhookSignatures signatures;
	private final ObjectMapper json;

	public PaymentWebhookController(PaymentWebhookService webhooks, WebhookSignatures signatures,
			ObjectMapper json) {
		this.webhooks = webhooks;
		this.signatures = signatures;
		this.json = json;
	}

	/**
	 * Where the payment provider reports what happened.
	 *
	 * <h2>The raw body, not the parsed object</h2>
	 *
	 * The signature covers the exact bytes the provider sent. Re-serialising a
	 * parsed object produces different bytes -- different key order, different
	 * whitespace -- and the signature would never match. So the body arrives as a
	 * string, is verified as a string, and only then parsed.
	 *
	 * <h2>Why the answers are what they are</h2>
	 *
	 * A bad signature is 401 and nothing else happens. Anything this system
	 * genuinely cannot act on is still 200: a provider reads a 4xx or 5xx as
	 * "try again", and retrying will not conjure up a payment that was never
	 * created here. Only a real failure on our side deserves an error, because
	 * that is the case where retrying might actually work.
	 */
	@PostMapping
	public ResponseEntity<Map<String, Object>> receive(
			@RequestBody String rawBody,
			@RequestHeader(name = SIGNATURE_HEADER, required = false) String signature) {

		if (!signatures.isValid(rawBody, signature)) {
			log.warn("Rejected a webhook with an invalid signature");
			return ResponseEntity.status(401).body(Map.of("received", false, "reason", "bad signature"));
		}

		PaymentWebhookPayload payload;
		try {
			payload = json.readValue(rawBody, PaymentWebhookPayload.class);
		}
		catch (Exception unreadable) {
			log.warn("Rejected an unreadable webhook body", unreadable);
			return ResponseEntity.badRequest().body(Map.of("received", false, "reason", "unreadable"));
		}

		boolean acted = webhooks.handle(payload, rawBody);
		return ResponseEntity.ok(Map.of("received", true, "acted", acted));
	}

}
