package com.seatly.event;

import com.seatly.support.IntegrationTest;
import com.seatly.venue.Seat;
import com.seatly.venue.SeatSection;
import com.seatly.venue.Venue;
import com.seatly.venue.VenueRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The same endpoints, this time over the real schema and real queries.
 * <p>
 * This is where the rule that a lapsed hold reads as available gets proved --
 * it depends on the query, the entity and the clock agreeing with each other,
 * which a mocked service could never show.
 */
@AutoConfigureMockMvc
@Transactional
class EventApiIntegrationTests extends IntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private VenueRepository venues;

	@Autowired
	private EventRepository events;

	@Autowired
	private EventSeatRepository eventSeats;

	@Test
	void anOnSaleEventAppearsWithItsAvailableSeatCount() throws Exception {
		Event event = fixture();

		mockMvc.perform(get("/api/events"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == %d)].title".formatted(event.getId()))
						.value("Test Performance"))
				.andExpect(jsonPath("$[?(@.id == %d)].venueName".formatted(event.getId()))
						.value("Test Hall"))
				.andExpect(jsonPath("$[?(@.id == %d)].availableSeats".formatted(event.getId()))
						.value(5));
	}

	@Test
	void theSeatMapComesBackGroupedIntoSectionsAndRows() throws Exception {
		Event event = fixture();

		mockMvc.perform(get("/api/events/{id}/seats", event.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Test Performance"))
				.andExpect(jsonPath("$.currency").value("INR"))
				.andExpect(jsonPath("$.sections.length()").value(2))
				.andExpect(jsonPath("$.sections[0].name").value("Stalls"))
				.andExpect(jsonPath("$.sections[0].rows[0].seats.length()").value(3))
				.andExpect(jsonPath("$.sections[0].rows[0].seats[0].label").value("A1"))
				.andExpect(jsonPath("$.sections[0].rows[0].seats[0].priceMinor").value(120000))
				.andExpect(jsonPath("$.sections[1].name").value("Balcony"));
	}

	@Test
	void aLiveHoldIsReportedAsHeld() throws Exception {
		Event event = fixture();
		EventSeat first = eventSeats.findSeatMap(event.getId()).get(0);
		first.holdUntil(Instant.now().plus(Duration.ofMinutes(5)));
		eventSeats.saveAndFlush(first);

		mockMvc.perform(get("/api/events/{id}/seats", event.getId()))
				.andExpect(jsonPath("$.sections[0].rows[0].seats[0].status").value("HELD"));

		mockMvc.perform(get("/api/events"))
				.andExpect(jsonPath("$[?(@.id == %d)].availableSeats".formatted(event.getId()))
						.value(4));
	}

	/**
	 * The interesting case: the row still says HELD, but its deadline has passed
	 * and nothing has tidied it up. The seat is for sale again regardless.
	 */
	@Test
	void aLapsedHoldIsReportedAsAvailable() throws Exception {
		Event event = fixture();
		EventSeat first = eventSeats.findSeatMap(event.getId()).get(0);
		first.holdUntil(Instant.now().minus(Duration.ofSeconds(30)));
		eventSeats.saveAndFlush(first);

		mockMvc.perform(get("/api/events/{id}/seats", event.getId()))
				.andExpect(jsonPath("$.sections[0].rows[0].seats[0].status").value("AVAILABLE"));

		mockMvc.perform(get("/api/events"))
				.andExpect(jsonPath("$[?(@.id == %d)].availableSeats".formatted(event.getId()))
						.value(5));
	}

	@Test
	void aSoldSeatStaysSold() throws Exception {
		Event event = fixture();
		EventSeat first = eventSeats.findSeatMap(event.getId()).get(0);
		first.markSold();
		eventSeats.saveAndFlush(first);

		mockMvc.perform(get("/api/events/{id}/seats", event.getId()))
				.andExpect(jsonPath("$.sections[0].rows[0].seats[0].status").value("SOLD"));
	}

	@Test
	void anUnknownEventIsA404ProblemDocument() throws Exception {
		mockMvc.perform(get("/api/events/{id}/seats", 999_999))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://seatly.dev/problems/not-found"));
	}

	/** A two-section hall with five seats, on sale now. */
	private Event fixture() {
		Venue venue = new Venue("Test Hall", "Mumbai");
		SeatSection stalls = venue.addSection("Stalls", 1);
		stalls.addSeat("A", 1);
		stalls.addSeat("A", 2);
		stalls.addSeat("A", 3);
		SeatSection balcony = venue.addSection("Balcony", 2);
		balcony.addSeat("E", 1);
		balcony.addSeat("E", 2);
		venues.save(venue);

		Instant now = Instant.now();
		Event event = new Event(venue, "Test Performance",
				now.plus(Duration.ofDays(7)),
				now.minus(Duration.ofHours(1)),
				now.plus(Duration.ofDays(6)));
		event.openSales();
		events.save(event);

		for (SeatSection section : venue.getSections()) {
			long priceMinor = section.getName().equals("Stalls") ? 120_000L : 60_000L;
			for (Seat seat : section.getSeats()) {
				eventSeats.save(new EventSeat(event, seat, priceMinor));
			}
		}
		eventSeats.flush();
		return event;
	}

}
