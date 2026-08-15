package com.seatly.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatly.account.AppUser;
import com.seatly.booking.BookingRequest;
import com.seatly.common.idempotency.IdempotencyRecordRepository;
import com.seatly.event.Event;
import com.seatly.event.EventSeat;
import com.seatly.support.IntegrationTest;
import com.seatly.support.SeatlyFixtures;
import com.seatly.support.TestAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The payment endpoints over HTTP: idempotency keys, and webhook signatures.
 * <p>
 * Not transactional -- the idempotency claim commits in its own transaction on
 * purpose, so a test wrapping everything in one would not see what a real caller
 * sees.
 */
@AutoConfigureMockMvc
class PaymentApiTests extends IntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private WebhookSignatures signatures;

	@Autowired
	private PaymentRepository payments;

	@Autowired
	private IdempotencyRecordRepository idempotencyRecords;

	@Autowired
	private SeatlyFixtures fixtures;

	@Autowired
	private TestAccounts accounts;

	private final ObjectMapper json = new ObjectMapper();

	private AppUser customer;
	private Event event;
	private List<EventSeat> seats;

	@BeforeEach
	void setUp() {
		fixtures.wipe();
		customer = accounts.customer();
		event = fixtures.onSaleEvent();
		seats = fixtures.seatsOf(event);
	}

	@Test
	void opensAPaymentForAHeldBooking() throws Exception {
		String reference = hold();

		mockMvc.perform(post("/api/payments/intents/{reference}", reference).with(as(customer)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paymentReference").exists())
				.andExpect(jsonPath("$.amountMinor").value(120000))
				.andExpect(jsonPath("$.status").value("REQUIRES_PAYMENT"));
	}

	/**
	 * The point of an idempotency key: a client that never saw the first reply
	 * sends the same request again and gets the same answer, not a second
	 * payment.
	 */
	@Test
	void repeatingARequestWithTheSameKeyReturnsTheFirstAnswer() throws Exception {
		String reference = hold();
		String key = UUID.randomUUID().toString();

		String first = mockMvc.perform(post("/api/payments/intents/{reference}", reference)
						.with(as(customer)).header("Idempotency-Key", key))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		String second = mockMvc.perform(post("/api/payments/intents/{reference}", reference)
						.with(as(customer)).header("Idempotency-Key", key))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(second).isEqualTo(first);
		assertThat(payments.count()).isEqualTo(1);
		assertThat(idempotencyRecords.count()).isEqualTo(1);
	}

	/**
	 * The same key with different content is a client bug. Replaying the old
	 * answer would report success for something that never happened.
	 */
	@Test
	void reusingAKeyForADifferentRequestIsRefused() throws Exception {
		String first = hold();
		String second = hold(seats.get(1));
		String key = UUID.randomUUID().toString();

		mockMvc.perform(post("/api/payments/intents/{reference}", first)
						.with(as(customer)).header("Idempotency-Key", key))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/payments/intents/{reference}", second)
						.with(as(customer)).header("Idempotency-Key", key))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.type").value("https://seatly.dev/problems/idempotency-key-reused"));
	}

	/** Keys belong to an account, so two customers may pick the same one. */
	@Test
	void twoCustomersMayUseTheSameKey() throws Exception {
		String mine = hold();
		AppUser other = accounts.customer();
		String theirs = holdAs(other, seats.get(1));
		String key = "the-same-key";

		mockMvc.perform(post("/api/payments/intents/{reference}", mine)
						.with(as(customer)).header("Idempotency-Key", key))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/payments/intents/{reference}", theirs)
						.with(as(other)).header("Idempotency-Key", key))
				.andExpect(status().isOk());

		assertThat(payments.count()).isEqualTo(2);
	}

	@Test
	void aWebhookWithoutAValidSignatureIsRejected() throws Exception {
		String body = json.writeValueAsString(new PaymentWebhookPayload(
				"evt_forged", PaymentWebhookService.PAYMENT_SUCCEEDED, "pay_anything", null));

		mockMvc.perform(post("/api/payments/webhook")
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/api/payments/webhook")
						.header("X-Seatly-Signature", "not-the-right-signature")
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void aSignedWebhookIsAccepted() throws Exception {
		String reference = hold();
		String paymentReference = json.readTree(mockMvc
						.perform(post("/api/payments/intents/{reference}", reference).with(as(customer)))
						.andReturn().getResponse().getContentAsString())
				.get("paymentReference").asText();

		String body = json.writeValueAsString(new PaymentWebhookPayload(
				"evt_" + UUID.randomUUID(), PaymentWebhookService.PAYMENT_SUCCEEDED,
				paymentReference, null));

		mockMvc.perform(post("/api/payments/webhook")
						.header("X-Seatly-Signature", signatures.sign(body))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.acted").value(true));
	}

	/** The provider does not sign in, so the endpoint must not demand a token. */
	@Test
	void theWebhookEndpointDoesNotRequireAnAccount() throws Exception {
		String body = json.writeValueAsString(new PaymentWebhookPayload(
				"evt_" + UUID.randomUUID(), PaymentWebhookService.PAYMENT_SUCCEEDED,
				"pay_unknown", null));

		mockMvc.perform(post("/api/payments/webhook")
						.header("X-Seatly-Signature", signatures.sign(body))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.acted").value(false));
	}

	private String hold() throws Exception {
		return hold(seats.get(0));
	}

	private String hold(EventSeat seat) throws Exception {
		return holdAs(customer, seat);
	}

	private String holdAs(AppUser user, EventSeat seat) throws Exception {
		String created = mockMvc.perform(post("/api/bookings").with(as(user))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(
								new BookingRequest(event.getId(), List.of(seat.getId())))))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		return json.readTree(created).get("reference").asText();
	}

	private RequestPostProcessor as(AppUser user) {
		return jwt()
				.jwt(token -> token
						.subject(String.valueOf(user.getId()))
						.claim("role", user.getRole().name()))
				.authorities(new SimpleGrantedAuthority(user.getRole().authority()));
	}

}
