package com.seatly.payment;

import com.seatly.account.CurrentAccount;
import com.seatly.common.idempotency.IdempotencyService;
import com.seatly.payment.view.PaymentView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

	private final PaymentService payments;
	private final IdempotencyService idempotency;
	private final CurrentAccount currentAccount;

	public PaymentController(PaymentService payments, IdempotencyService idempotency,
			CurrentAccount currentAccount) {
		this.payments = payments;
		this.idempotency = idempotency;
		this.currentAccount = currentAccount;
	}

	/**
	 * Opens a payment for a held booking.
	 * <p>
	 * Honours an {@code Idempotency-Key} header. A client whose request times out
	 * cannot tell whether a payment was opened; sending the same key again returns
	 * the same answer rather than opening a second one.
	 * <p>
	 * The header is optional because this particular endpoint is already safe to
	 * repeat -- a booking may only have one open attempt, enforced by a partial
	 * unique index. The key is what makes that guarantee explicit to the caller
	 * instead of something they have to know about the implementation.
	 */
	@PostMapping("/intents/{bookingReference}")
	public PaymentView startPayment(
			@PathVariable String bookingReference,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {

		return idempotency.runOnce(
				idempotencyKey,
				currentAccount.id(),
				Map.of("bookingReference", bookingReference),
				PaymentView.class,
				() -> payments.startPayment(bookingReference));
	}

	@GetMapping("/{paymentReference}")
	public PaymentView byReference(@PathVariable String paymentReference) {
		return payments.byReference(paymentReference);
	}

}
