package com.seatly.support;

import com.seatly.event.Event;
import com.seatly.event.EventRepository;
import com.seatly.event.EventSeat;
import com.seatly.event.EventSeatRepository;
import com.seatly.venue.Seat;
import com.seatly.venue.SeatSection;
import com.seatly.venue.Venue;
import com.seatly.venue.VenueRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Builds a small on-sale event for tests to work against.
 */
@Component
public class SeatlyFixtures {

	private final VenueRepository venues;
	private final EventRepository events;
	private final EventSeatRepository eventSeats;
	private final JdbcTemplate jdbc;

	public SeatlyFixtures(VenueRepository venues, EventRepository events,
			EventSeatRepository eventSeats, JdbcTemplate jdbc) {
		this.venues = venues;
		this.events = events;
		this.eventSeats = eventSeats;
		this.jdbc = jdbc;
	}

	/** A three-seat hall with one event on sale, priced at Rs 1,200 a seat. */
	public Event onSaleEvent() {
		Venue venue = new Venue("Test Hall " + UUID.randomUUID(), "Mumbai");
		SeatSection stalls = venue.addSection("Stalls", 1);
		stalls.addSeat("A", 1);
		stalls.addSeat("A", 2);
		stalls.addSeat("A", 3);
		venues.save(venue);

		Instant now = Instant.now();
		Event event = new Event(venue, "Test Performance",
				now.plus(Duration.ofDays(7)),
				now.minus(Duration.ofHours(1)),
				now.plus(Duration.ofDays(6)));
		event.openSales();
		events.save(event);

		for (Seat seat : venue.getSections().get(0).getSeats()) {
			eventSeats.save(new EventSeat(event, seat, 120_000L));
		}
		return event;
	}

	public List<EventSeat> seatsOf(Event event) {
		return eventSeats.findSeatMap(event.getId());
	}

	/**
	 * Wipes every domain table.
	 * <p>
	 * Needed by tests that cannot roll back -- a concurrency test has to commit
	 * its transactions for the race to exist at all.
	 */
	public void wipe() {
		jdbc.execute("""
				truncate table booking_seat, booking, event_seat, seat, seat_section, event, venue
				restart identity cascade
				""");
	}

}
