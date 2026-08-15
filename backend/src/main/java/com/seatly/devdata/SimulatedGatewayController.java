package com.seatly.devdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatly.payment.PaymentWebhookPayload;
import com.seatly.payment.PaymentWebhookService;
import com.seatly.payment.WebhookSignatures;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Stands in for the payment provider, so the flow can be driven end to end
 * without one.
 *
 * <h2>Only under the {@code seed} profile</h2>
 *
 * This endpoint settles a payment on request, which in a deployment would mean
 * anybody could pay for anything by asking nicely. It does not exist outside
 * local development, and the guard is the profile rather than a role: a
 * misconfigured role is a bug, an absent bean cannot be called at all.
 * <p>
 * What it does not do is shortcut anything. It builds the same payload a
 * provider would send, signs it with the same secret, and hands it to the same
 * handler. The only fiction is who decided the payment succeeded.
 */
@RestController
@Profile("seed")
public class SimulatedGatewayController {

	private final PaymentWebhookService webhooks;
	private final WebhookSignatures signatures;
	private final ObjectMapper json;

	public SimulatedGatewayController(PaymentWebhookService webhooks, WebhookSignatures signatures,
			ObjectMapper json) {
		this.webhooks = webhooks;
		this.signatures = signatures;
		this.json = json;
	}

	@PostMapping("/api/dev/payments/{paymentReference}/settle")
	public Map<String, Object> settle(
			@PathVariable String paymentReference,
			@RequestParam(defaultValue = "succeeded") String outcome,
			@RequestParam(required = false) String eventId) throws Exception {

		PaymentWebhookPayload payload = new PaymentWebhookPayload(
				eventId != null ? eventId : "evt_" + UUID.randomUUID(),
				"succeeded".equals(outcome)
						? PaymentWebhookService.PAYMENT_SUCCEEDED
						: PaymentWebhookService.PAYMENT_FAILED,
				paymentReference,
				"succeeded".equals(outcome) ? null : "Card declined");

		String body = json.writeValueAsString(payload);
		boolean acted = webhooks.handle(payload, body);

		return Map.of(
				"acted", acted,
				"signatureThatWouldHaveBeenSent", signatures.sign(body));
	}

}
