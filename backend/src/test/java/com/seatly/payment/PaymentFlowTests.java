package com.seatly.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatly.account.AppUser;
import com.seatly.booking.BookingRepository;
import com.seatly.booking.BookingRequest;
import com.seatly.booking.BookingService;
import com.seatly.booking.BookingStatus;
import com.seatly.booking.BookingView;
import com.seatly.common.outbox.OutboxMessageRepository;
import com.seatly.event.Event;
import com.seatly.event.EventSeat;
import com.seatly.event.EventSeatRepository;
import com.seatly.event.EventSeatStatus;
import com.seatly.payment.view.PaymentView;
import com.seatly.support.IntegrationTest;
import com.seatly.support.SeatlyFixtures;
import com.seatly.support.TestAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A payment from opening it to the provider's callback.
 */
@Transactional
class PaymentFlowTests extends IntegrationTest {

	@Autowired
	private BookingService bookings;

	@Autowired
	private BookingRepository bookingRepository;

	@Autowired
	private PaymentService payments;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private PaymentWebhookService webhooks;

	@Autowired
	private EventSeatRepository eventSeats;

	@Autowired
	private OutboxMessageRepository outbox;

	@Autowired
	private SeatlyFixtures fixtures;

	@Autowired
	private TestAccounts accounts;

	private final ObjectMapper json = new ObjectMapper();

	private AppUser customer;
	private Event event;
	private List<EventSeat> seats;

	@BeforeEach
	void signIn() {
		fixtures.wipe();
		customer = accounts.customer();
		accounts.actAs(customer);
		event = fixtures.onSaleEvent();
		seats = fixtures.seatsOf(event);
	}

	@AfterEach
	void signOut() {
		accounts.signOut();
	}

	@Test
	void openingAPaymentTakesItsAmountFromTheBooking() {
		BookingView held = hold();

		PaymentView payment = payments.startPayment(held.reference());

		assertThat(payment.paymentReference()).startsWith("pay_");
		assertThat(payment.amountMinor()).isEqualTo(held.totalMinor());
		assertThat(payment.status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT);
	}

	/** Asking twice must not open a second attempt against the same booking. */
	@Test
	void openingAPaymentTwiceReturnsTheSameAttempt() {
		BookingView held = hold();

		PaymentView first = payments.startPayment(held.reference());
		PaymentView second = payments.startPayment(held.reference());

		assertThat(second.paymentReference()).isEqualTo(first.paymentReference());
		assertThat(paymentRepository.count()).isEqualTo(1);
	}

	@Test
	void aSuccessfulPaymentSellsTheSeats() {
		BookingView held = hold();
		PaymentView payment = payments.startPayment(held.reference());

		boolean acted = deliver(succeeded(payment));

		assertThat(acted).isTrue();
		assertThat(bookingRepository.findByReference(held.reference()).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.CONFIRMED);
		assertThat(eventSeats.findById(seats.get(0).getId()).orElseThrow().getStatus())
				.isEqualTo(EventSeatStatus.SOLD);
		assertThat(paymentRepository.findByProviderReference(payment.paymentReference())
				.orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
	}

	/**
	 * The behaviour the whole webhook table exists for. Providers deliver at
	 * least once, and the second delivery must change nothing.
	 */
	@Test
	void deliveringTheSameEventTwiceOnlyCountsOnce() {
		BookingView held = hold();
		PaymentView payment = payments.startPayment(held.reference());
		PaymentWebhookPayload event = succeeded(payment);

		assertThat(deliver(event)).isTrue();
		assertThat(deliver(event)).isFalse();

		assertThat(bookingRepository.findByReference(held.reference()).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.CONFIRMED);
		// One confirmation email, not two.
		assertThat(outbox.count()).isEqualTo(1);
	}

	/** A different event id for a payment already settled is also a no-op. */
	@Test
	void aSecondSuccessForAnAlreadySettledPaymentChangesNothing() {
		BookingView held = hold();
		PaymentView payment = payments.startPayment(held.reference());
		deliver(succeeded(payment));

		assertThat(deliver(succeeded(payment))).isFalse();
		assertThat(outbox.count()).isEqualTo(1);
	}

	@Test
	void aFailedPaymentLeavesTheSeatsHeld() {
		BookingView held = hold();
		PaymentView payment = payments.startPayment(held.reference());

		deliver(new PaymentWebhookPayload("evt_" + UUID.randomUUID(),
				PaymentWebhookService.PAYMENT_FAILED, payment.paymentReference(), "Card declined"));

		assertThat(paymentRepository.findByProviderReference(payment.paymentReference())
				.orElseThrow().getFailureReason()).isEqualTo("Card declined");
		assertThat(bookingRepository.findByReference(held.reference()).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.PENDING);
		assertThat(eventSeats.findById(seats.get(0).getId()).orElseThrow().getStatus())
				.isEqualTo(EventSeatStatus.HELD);
	}

	/**
	 * Acknowledged, not rejected. A 4xx would make the provider retry forever for
	 * a payment this system has never issued.
	 */
	@Test
	void aWebhookForAnUnknownPaymentIsAcknowledgedAndIgnored() {
		assertThat(deliver(new PaymentWebhookPayload("evt_" + UUID.randomUUID(),
				PaymentWebhookService.PAYMENT_SUCCEEDED, "pay_never_issued", null))).isFalse();
	}

	@Test
	void confirmingWritesTheTicketEmailIntoTheOutbox() {
		BookingView held = hold();
		PaymentView payment = payments.startPayment(held.reference());

		deliver(succeeded(payment));

		assertThat(outbox.findAll()).singleElement().satisfies(message -> {
			assertThat(message.getMessageType()).isEqualTo("booking.confirmed");
			assertThat(message.getRecipient()).isEqualTo(customer.getEmail());
			assertThat(message.getPayload()).contains(held.reference()).contains("A1");
			// Written, not sent. The publisher does that after the commit.
			assertThat(message.isSent()).isFalse();
		});
	}

	private BookingView hold() {
		return bookings.hold(new BookingRequest(event.getId(), List.of(seats.get(0).getId())));
	}

	private PaymentWebhookPayload succeeded(PaymentView payment) {
		return new PaymentWebhookPayload("evt_" + UUID.randomUUID(),
				PaymentWebhookService.PAYMENT_SUCCEEDED, payment.paymentReference(), null);
	}

	private boolean deliver(PaymentWebhookPayload payload) {
		try {
			return webhooks.handle(payload, json.writeValueAsString(payload));
		}
		catch (Exception unserialisable) {
			throw new IllegalStateException(unserialisable);
		}
	}

}
