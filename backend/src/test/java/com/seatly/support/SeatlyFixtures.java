package com.seatly.support;

import com.seatly.event.Event;
import com.seatly.event.EventRepository;
import com.seatly.event.EventSeat;
import com.seatly.event.EventSeatRepository;
import com.seatly.venue.Seat;
import com.seatly.venue.SeatSection;
import com.seatly.venue.Venue;
import com.seatly.venue.VenueRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
	private final StringRedisTemplate redis;

	public SeatlyFixtures(VenueRepository venues, EventRepository events,
			EventSeatRepository eventSeats, JdbcTemplate jdbc, StringRedisTemplate redis) {
		this.venues = venues;
		this.events = events;
		this.eventSeats = eventSeats;
		this.jdbc = jdbc;
		this.redis = redis;
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

	/** Disables an account without going through any service. */
	public void disableAccount(Long userId) {
		jdbc.update("update app_user set enabled = false where id = ?", userId);
	}

	/**
	 * Wipes every domain table, and the Redis guard keys with them.
	 * <p>
	 * Needed by tests that cannot roll back -- a concurrency test has to commit
	 * its transactions for the race to exist at all.
	 * <p>
	 * Redis has to be cleared too. {@code restart identity} hands the next test
	 * the same seat ids, and a guard key left over from the previous one would
	 * turn its first caller away for a seat that no longer exists.
	 */
	public void wipe() {
		jdbc.execute("""
				truncate table booking_seat, booking, event_seat, seat, seat_section, event, venue,
				               refresh_token, app_user, payment, idempotency_key, webhook_event,
				               outbox_message
				restart identity cascade
				""");

		Set<String> guardKeys = redis.keys("seatly:hold:*");
		if (guardKeys != null && !guardKeys.isEmpty()) {
			redis.delete(guardKeys);
		}
	}

}
