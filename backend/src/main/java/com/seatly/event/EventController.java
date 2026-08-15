package com.seatly.event;

import com.seatly.event.view.EventSummary;
import com.seatly.event.view.SeatMapView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/events")
public class EventController {

	private final EventCatalogService catalogue;

	public EventController(EventCatalogService catalogue) {
		this.catalogue = catalogue;
	}

	/** Events a customer can buy into right now. */
	@GetMapping
	public List<EventSummary> onSale() {
		return catalogue.onSaleEvents();
	}

	/** The full seating chart for one event, in hall order. */
	@GetMapping("/{eventId}/seats")
	public SeatMapView seatMap(@PathVariable Long eventId) {
		return catalogue.seatMap(eventId);
	}

}
