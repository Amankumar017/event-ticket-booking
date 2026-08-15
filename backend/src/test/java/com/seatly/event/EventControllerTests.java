package com.seatly.event;

import com.seatly.account.SecurityConfiguration;
import com.seatly.common.NotFoundException;
import com.seatly.event.view.EventSummary;
import com.seatly.event.view.SeatMapView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web slice: routing, serialisation and error translation only.
 * <p>
 * No database and no Spring Data here, the service is mocked, so a failure in
 * this class points at the HTTP layer and nothing else. The trade is that it
 * proves nothing about whether the query is right, which is what
 * {@code EventApiIntegrationTests} is for.
 */
// The slice loads controllers, not arbitrary configuration, so without this
// import the default "deny everything" chain applies and every browse is a 401.
@WebMvcTest(EventController.class)
@Import(SecurityConfiguration.class)
class EventControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private EventCatalogService catalogue;

	@Test
	void listsOnSaleEvents() throws Exception {
		given(catalogue.onSaleEvents()).willReturn(List.of(new EventSummary(
				7L, "An Evening of Hindustani Classical", "Prithvi Playhouse", "Mumbai",
				Instant.parse("2026-09-05T13:30:00Z"), Instant.parse("2026-09-04T13:30:00Z"), 68L)));

		mockMvc.perform(get("/api/events"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(7))
				.andExpect(jsonPath("$[0].venueName").value("Prithvi Playhouse"))
				.andExpect(jsonPath("$[0].availableSeats").value(68));
	}

	@Test
	void returnsTheSeatMapGroupedBySectionAndRow() throws Exception {
		given(catalogue.seatMap(7L)).willReturn(new SeatMapView(
				7L, "An Evening of Hindustani Classical", "Prithvi Playhouse", "Mumbai",
				Instant.parse("2026-09-05T13:30:00Z"), "INR",
				List.of(new SeatMapView.SectionView("Stalls", 1, List.of(
						new SeatMapView.RowView("A", List.of(
								new SeatMapView.SeatView(101L, 1, "A1", EventSeatStatus.AVAILABLE, 120_000L),
								new SeatMapView.SeatView(102L, 2, "A2", EventSeatStatus.SOLD, 120_000L))))))));

		mockMvc.perform(get("/api/events/7/seats"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.currency").value("INR"))
				.andExpect(jsonPath("$.sections[0].name").value("Stalls"))
				.andExpect(jsonPath("$.sections[0].rows[0].label").value("A"))
				.andExpect(jsonPath("$.sections[0].rows[0].seats[0].label").value("A1"))
				.andExpect(jsonPath("$.sections[0].rows[0].seats[1].status").value("SOLD"));
	}

	@Test
	void reportsAnUnknownEventAsAProblemDocument() throws Exception {
		given(catalogue.seatMap(any())).willThrow(NotFoundException.of("Event", 999));

		mockMvc.perform(get("/api/events/999/seats"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Not found"))
				.andExpect(jsonPath("$.type").value("https://seatly.dev/problems/not-found"))
				.andExpect(jsonPath("$.detail").value("Event 999 was not found"));
	}

}
