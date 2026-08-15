package com.seatly.event;

import com.seatly.common.NotFoundException;
import com.seatly.event.view.EventSummary;
import com.seatly.event.view.SeatMapView;
import com.seatly.venue.Seat;
import com.seatly.venue.SeatSection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read side of the catalogue: what is on sale, and what the hall looks like.
 * <p>
 * Every method takes its notion of "now" from an injected {@link Clock} rather
 * than calling {@code Instant.now()} inline. That one indirection is what makes
 * expiry testable -- a test can hand this service a clock positioned after a
 * hold has lapsed instead of sleeping through it.
 */
@Service
@Transactional(readOnly = true)
public class EventCatalogService {

	private final EventRepository events;
	private final EventSeatRepository eventSeats;
	private final Clock clock;

	public EventCatalogService(EventRepository events, EventSeatRepository eventSeats, Clock clock) {
		this.events = events;
		this.eventSeats = eventSeats;
		this.clock = clock;
	}

	public List<EventSummary> onSaleEvents() {
		Instant now = clock.instant();
		List<Event> onSale = events.findOnSaleAt(now);
		if (onSale.isEmpty()) {
			return List.of();
		}

		List<Long> ids = onSale.stream().map(Event::getId).toList();
		Map<Long, Long> available = eventSeats.countAvailableForEvents(ids, now).stream()
				.collect(Collectors.toMap(AvailableSeatCount::eventId, AvailableSeatCount::available));

		return onSale.stream()
				.map(event -> new EventSummary(
						event.getId(),
						event.getTitle(),
						event.getVenue().getName(),
						event.getVenue().getCity(),
						event.getStartsAt(),
						event.getSalesCloseAt(),
						available.getOrDefault(event.getId(), 0L)))
				.toList();
	}

	public SeatMapView seatMap(Long eventId) {
		Event event = events.findById(eventId)
				.orElseThrow(() -> NotFoundException.of("Event", eventId));

		Instant now = clock.instant();
		List<EventSeat> seats = eventSeats.findSeatMap(eventId);

		// The query already returns hall order, so grouping into a LinkedHashMap
		// preserves it without a second sort.
		Map<SeatSection, List<EventSeat>> bySection = seats.stream()
				.collect(Collectors.groupingBy(
						seat -> seat.getSeat().getSection(),
						LinkedHashMap::new,
						Collectors.toList()));

		List<SeatMapView.SectionView> sections = new ArrayList<>();
		bySection.forEach((section, sectionSeats) -> sections.add(
				new SeatMapView.SectionView(section.getName(), section.getDisplayOrder(), toRows(sectionSeats, now))));

		return new SeatMapView(
				event.getId(),
				event.getTitle(),
				event.getVenue().getName(),
				event.getVenue().getCity(),
				event.getStartsAt(),
				seats.isEmpty() ? "INR" : seats.get(0).getCurrency(),
				sections);
	}

	private List<SeatMapView.RowView> toRows(List<EventSeat> sectionSeats, Instant now) {
		Map<String, List<EventSeat>> byRow = sectionSeats.stream()
				.collect(Collectors.groupingBy(
						seat -> seat.getSeat().getRowLabel(),
						LinkedHashMap::new,
						Collectors.toList()));

		return byRow.entrySet().stream()
				.map(entry -> new SeatMapView.RowView(
						entry.getKey(),
						entry.getValue().stream().map(seat -> toSeatView(seat, now)).toList()))
				.toList();
	}

	private SeatMapView.SeatView toSeatView(EventSeat eventSeat, Instant now) {
		Seat seat = eventSeat.getSeat();
		return new SeatMapView.SeatView(
				eventSeat.getId(),
				seat.getSeatNumber(),
				seat.label(),
				eventSeat.effectiveStatusAt(now),
				eventSeat.getPriceMinor());
	}

}
