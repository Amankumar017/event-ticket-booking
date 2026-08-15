package com.seatly.payment;

import com.seatly.booking.Booking;
import com.seatly.booking.BookingService;
import com.seatly.booking.PaymentArrivedTooLateException;
import com.seatly.common.metrics.SeatlyMetrics;
import com.seatly.common.outbox.OutboxMessage;
import com.seatly.common.outbox.OutboxMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Applies what the payment provider tells us.
 *
 * <h2>Deliveries repeat, so this must not</h2>
 *
 * Every provider worth using delivers at least once: if the acknowledgement is
 * lost on the way back, the same event arrives again. Handling it twice would
 * confirm a booking twice, and in a system that sends tickets and takes seats
 * out of inventory, twice is not the same as once.
 * <p>
 * Each delivery is therefore recorded by the provider's own event id, behind a
 * unique constraint. The second arrival loses that race, is acknowledged, and
 * does nothing else.
 */
@Service
public class PaymentWebhookService {

	private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

	public static final String PAYMENT_SUCCEEDED = "payment.succeeded";
	public static final String PAYMENT_FAILED = "payment.failed";

	private final WebhookEventRepository events;
	private final PaymentRepository payments;
	private final BookingService bookings;
	private final OutboxMessageRepository outbox;
	private final SeatlyMetrics metrics;
	private final Clock clock;

	public PaymentWebhookService(WebhookEventRepository events, PaymentRepository payments,
			BookingService bookings, OutboxMessageRepository outbox, SeatlyMetrics metrics, Clock clock) {
		this.events = events;
		this.payments = payments;
		this.bookings = bookings;
		this.outbox = outbox;
		this.metrics = metrics;
		this.clock = clock;
	}

	/**
	 * Handles one delivery.
	 *
	 * @return whether this delivery did any work; false means it was a repeat
	 */
	@Transactional
	public boolean handle(PaymentWebhookPayload payload, String rawBody) {
		Instant now = clock.instant();

		if (events.existsByProviderEventId(payload.eventId())) {
			log.info("Ignoring repeat delivery of webhook {}", payload.eventId());
			metrics.webhookDelivery("duplicate");
			return false;
		}

		// Checked first rather than inserted-and-caught. Catching the constraint
		// violation would leave the persistence context holding an entity that
		// was never assigned an id, and the commit at the end of this method
		// would then fail on a flush that has nothing to do with the duplicate.
		//
		// The unique index is still the guarantee. Two deliveries arriving at the
		// same instant both pass the check, one loses at the index, and its
		// transaction rolls back whole -- so the provider retries, finds the row
		// this time, and skips cleanly. Nothing is half-applied either way.
		WebhookEvent event = events.save(new WebhookEvent(payload.eventId(), payload.type(), rawBody));

		Payment payment = payments.findByProviderReference(payload.paymentReference()).orElse(null);
		if (payment == null) {
			// Acknowledged rather than rejected. A 4xx makes the provider retry
			// forever for a payment this system has never heard of, and nothing
			// about retrying would help.
			log.warn("Webhook {} refers to unknown payment {}",
					payload.eventId(), payload.paymentReference());
			metrics.webhookDelivery("unknown-payment");
			event.markProcessed(now);
			return false;
		}

		if (payment.isSettled()) {
			// A different delivery already settled this one. Not a repeat of the
			// same event, so it is recorded, but there is nothing left to do.
			log.info("Payment {} is already {}", payment.getProviderReference(), payment.getStatus());
			metrics.webhookDelivery("already-settled");
			event.markProcessed(now);
			return false;
		}

		switch (payload.type()) {
			case PAYMENT_SUCCEEDED -> settle(payment, now);
			case PAYMENT_FAILED -> payment.failAt(now, payload.reason());
			default -> log.info("Nothing to do for webhook type {}", payload.type());
		}

		event.markProcessed(now);
		metrics.webhookDelivery("applied");
		return true;
	}

	/**
	 * Money received: the seats are sold.
	 * <p>
	 * The booking is confirmed through the same service the customer path uses,
	 * so the seat locking and the state checks are identical. What differs is
	 * only who is asking -- there is no signed-in account behind a webhook.
	 */
	private void settle(Payment payment, Instant now) {
		payment.succeedAt(now);
		Booking booking = payment.getBooking();

		try {
			bookings.confirmPaidBooking(booking.getReference(), now);
		}
		catch (PaymentArrivedTooLateException tooLate) {
			// The money is real and the seats are gone. Denying the payment would
			// be a lie, and confirming the booking would oversell a chair somebody
			// else now holds. The only honest outcome is a refund, so one is
			// queued in the same transaction that records the payment.
			log.warn("Refund required: {}", tooLate.getMessage());
			outbox.save(new OutboxMessage(
					"refund.required",
					booking.getCustomerEmail(),
					"Payment %s succeeded for booking %s, which is %s. Refund %d %s."
							.formatted(payment.getProviderReference(), booking.getReference(),
									booking.getStatus().name().toLowerCase(),
									payment.getAmountMinor(), payment.getCurrency())));
		}
	}

}
