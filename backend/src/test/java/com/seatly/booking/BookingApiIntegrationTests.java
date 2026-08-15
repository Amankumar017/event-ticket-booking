package com.seatly.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatly.account.AppUser;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Transactional
class BookingApiIntegrationTests extends IntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	/** Boot 4 does not publish an ObjectMapper bean; these payloads are plain records. */
	private final ObjectMapper json = new ObjectMapper();

	@Autowired
	private SeatlyFixtures fixtures;

	@Autowired
	private TestAccounts accounts;

	private AppUser customer;

	@BeforeEach
	void createAccount() {
		customer = accounts.customer();
	}

	@Test
	void holdsSeatsAndReturns201() throws Exception {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);

		mockMvc.perform(post("/api/bookings").with(as(customer))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(event, seats.get(0), seats.get(1))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.expiresAt").isNotEmpty())
				.andExpect(jsonPath("$.totalMinor").value(240000))
				.andExpect(jsonPath("$.seats.length()").value(2));
	}

	/** Browsing stays open; buying does not. */
	@Test
	void holdingWithoutSigningInIsRejected() throws Exception {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);

		mockMvc.perform(post("/api/bookings")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(event, seats.get(0))))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/api/events/{id}/seats", event.getId()))
				.andExpect(status().isOk());
	}

	@Test
	void cancellingAHoldPutsTheSeatBackOnSale() throws Exception {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		String reference = holdReference(event, seats.get(0));

		mockMvc.perform(post("/api/bookings/{reference}/cancellation", reference).with(as(customer)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));

		mockMvc.perform(get("/api/events/{id}/seats", event.getId()))
				.andExpect(jsonPath("$.sections[0].rows[0].seats[0].status").value("AVAILABLE"));
	}

	@Test
	void aHeldSeatShowsAsHeldOnTheSeatMap() throws Exception {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		holdReference(event, seats.get(0));

		mockMvc.perform(get("/api/events/{id}/seats", event.getId()))
				.andExpect(jsonPath("$.sections[0].rows[0].seats[0].status").value("HELD"))
				.andExpect(jsonPath("$.sections[0].rows[0].seats[1].status").value("AVAILABLE"));
	}

	@Test
	void refusesAnAlreadyHeldSeatWith409() throws Exception {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		String body = body(event, seats.get(0));

		mockMvc.perform(post("/api/bookings").with(as(customer))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated());

		// Detail is not asserted exactly: this request is turned away by the Redis
		// guard before it reaches the database, and either refusal is a correct
		// answer to the same question.
		mockMvc.perform(post("/api/bookings").with(as(accounts.customer()))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value("https://seatly.dev/problems/seat-unavailable"))
				.andExpect(jsonPath("$.detail").isNotEmpty());
	}

	/**
	 * Without an explicit handler this would have come back as a 500, blaming the
	 * server for the caller's mistake.
	 */
	@Test
	void reportsAnInvalidRequestAsA400WithFieldErrors() throws Exception {
		mockMvc.perform(post("/api/bookings").with(as(customer))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"eventId": null, "eventSeatIds": []}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://seatly.dev/problems/invalid-request"))
				.andExpect(jsonPath("$.errors.eventId").value("eventId is required"))
				.andExpect(jsonPath("$.errors.eventSeatIds").value("pick at least one seat"));
	}

	@Test
	void findsYourOwnBookingByReference() throws Exception {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		String reference = holdReference(event, seats.get(0));

		mockMvc.perform(get("/api/bookings/{reference}", reference).with(as(customer)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.seats[0].label").value("A1"));
	}

	/**
	 * Somebody else's booking reads as missing rather than forbidden. A 403 would
	 * confirm the reference exists, which is exactly what a guesser wants to know.
	 */
	@Test
	void cannotReadSomebodyElsesBooking() throws Exception {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		String reference = holdReference(event, seats.get(0));

		mockMvc.perform(get("/api/bookings/{reference}", reference).with(as(accounts.customer())))
				.andExpect(status().isNotFound());
	}

	@Test
	void cannotCancelSomebodyElsesHold() throws Exception {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		String reference = holdReference(event, seats.get(0));

		mockMvc.perform(post("/api/bookings/{reference}/cancellation", reference)
						.with(as(accounts.customer())))
				.andExpect(status().isNotFound());
	}

	@Test
	void listsYourOwnBookings() throws Exception {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		holdReference(event, seats.get(0));

		mockMvc.perform(get("/api/bookings/mine").with(as(customer)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));

		mockMvc.perform(get("/api/bookings/mine").with(as(accounts.customer())))
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void onlyAnOrganiserCanSeeEveryBookingForAnEvent() throws Exception {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		holdReference(event, seats.get(0));

		mockMvc.perform(get("/api/bookings").param("eventId", event.getId().toString())
						.with(as(customer)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("https://seatly.dev/problems/forbidden"));

		mockMvc.perform(get("/api/bookings").param("eventId", event.getId().toString())
						.with(as(accounts.organiser())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void reportsAnUnknownReferenceAsA404() throws Exception {
		mockMvc.perform(get("/api/bookings/{reference}", "SEAT-NOPE1234").with(as(customer)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://seatly.dev/problems/not-found"));
	}

	/**
	 * A token shaped exactly like the one the auth endpoints issue, so these
	 * requests take the same path through the filter chain that a real one does.
	 */
	private RequestPostProcessor as(AppUser user) {
		return jwt()
				.jwt(token -> token
						.subject(String.valueOf(user.getId()))
						.claim("role", user.getRole().name()))
				.authorities(new SimpleGrantedAuthority(user.getRole().authority()));
	}

	private String body(Event event, EventSeat... seats) throws Exception {
		return json.writeValueAsString(new BookingRequest(
				event.getId(), List.of(seats).stream().map(EventSeat::getId).toList()));
	}

	/** Holds one seat as the default customer and hands back the reference. */
	private String holdReference(Event event, EventSeat seat) throws Exception {
		String created = mockMvc.perform(post("/api/bookings").with(as(customer))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(event, seat)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		return json.readTree(created).get("reference").asText();
	}

}
