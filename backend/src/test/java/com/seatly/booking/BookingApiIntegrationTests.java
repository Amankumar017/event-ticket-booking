package com.seatly.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatly.event.Event;
import com.seatly.event.EventSeat;
import com.seatly.support.IntegrationTest;
import com.seatly.support.SeatlyFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

	@Test
	void booksSeatsAndReturns201() throws Exception {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);

		mockMvc.perform(post("/api/bookings")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(new BookingRequest(
								event.getId(),
								List.of(seats.get(0).getId(), seats.get(1).getId()),
								"Aman", "aman@example.com"))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.totalMinor").value(240000))
				.andExpect(jsonPath("$.seats.length()").value(2));
	}

	@Test
	void refusesAnAlreadySoldSeatWith409() throws Exception {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);
		String body = json.writeValueAsString(new BookingRequest(
				event.getId(), List.of(seats.get(0).getId()), "Aman", "aman@example.com"));

		mockMvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value("https://seatly.dev/problems/seat-unavailable"))
				.andExpect(jsonPath("$.detail").value("Seat A1 is no longer available"));
	}

	/**
	 * Without an explicit handler this would have come back as a 500, blaming the
	 * server for the caller's mistake.
	 */
	@Test
	void reportsAnInvalidRequestAsA400WithFieldErrors() throws Exception {
		mockMvc.perform(post("/api/bookings")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"eventId": null, "eventSeatIds": [], "customerName": "",
								 "customerEmail": "not-an-email"}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://seatly.dev/problems/invalid-request"))
				.andExpect(jsonPath("$.errors.eventId").value("eventId is required"))
				.andExpect(jsonPath("$.errors.eventSeatIds").value("pick at least one seat"))
				.andExpect(jsonPath("$.errors.customerEmail").value("customerEmail must be an email address"));
	}

	@Test
	void findsABookingByReference() throws Exception {
		Event event = fixtures.onSaleEvent();
		List<EventSeat> seats = fixtures.seatsOf(event);

		String created = mockMvc.perform(post("/api/bookings")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(new BookingRequest(
								event.getId(), List.of(seats.get(0).getId()),
								"Aman", "aman@example.com"))))
				.andReturn().getResponse().getContentAsString();
		String reference = json.readTree(created).get("reference").asText();

		mockMvc.perform(get("/api/bookings/{reference}", reference))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.seats[0].label").value("A1"));
	}

	@Test
	void reportsAnUnknownReferenceAsA404() throws Exception {
		mockMvc.perform(get("/api/bookings/{reference}", "SEAT-NOPE1234"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://seatly.dev/problems/not-found"));
	}

}
