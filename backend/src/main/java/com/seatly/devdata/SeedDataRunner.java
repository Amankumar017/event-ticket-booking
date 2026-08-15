package com.seatly.devdata;

import com.seatly.event.Event;
import com.seatly.event.EventRepository;
import com.seatly.event.EventSeat;
import com.seatly.event.EventSeatRepository;
import com.seatly.venue.Seat;
import com.seatly.venue.SeatSection;
import com.seatly.venue.Venue;
import com.seatly.venue.VenueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Puts a small but realistic hall and one on-sale event into the database.
 * <p>
 * Guarded by the {@code seed} profile so it can never run anywhere it is not
 * wanted, and idempotent so that restarting the application does not stack up
 * duplicate venues. Run with:
 * <pre>./mvnw spring-boot:run -Dspring-boot.run.profiles=seed</pre>
 */
@Component
@Profile("seed")
public class SeedDataRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(SeedDataRunner.class);

	private static final String VENUE_NAME = "Prithvi Playhouse";

	private final VenueRepository venues;
	private final EventRepository events;
	private final EventSeatRepository eventSeats;

	public SeedDataRunner(VenueRepository venues, EventRepository events, EventSeatRepository eventSeats) {
		this.venues = venues;
		this.events = events;
		this.eventSeats = eventSeats;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (venues.existsByName(VENUE_NAME)) {
			log.info("Seed data already present, leaving it alone");
			return;
		}

		Venue venue = buildVenue();
		venues.save(venue);

		Instant now = Instant.now();
		Event event = new Event(
				venue,
				"An Evening of Hindustani Classical",
				now.plus(Duration.ofDays(21)),
				now.minus(Duration.ofDays(1)),
				now.plus(Duration.ofDays(20)));
		event.openSales();
		events.save(event);

		eventSeats.saveAll(priceSeats(event, venue));

		log.info("Seeded venue '{}' and event '{}' with {} seats",
				venue.getName(), event.getTitle(), eventSeats.count());
	}

	/** Two sections: a premium stalls block down front, a cheaper balcony behind. */
	private Venue buildVenue() {
		Venue venue = new Venue(VENUE_NAME, "Mumbai");

		SeatSection stalls = venue.addSection("Stalls", 1);
		for (String row : List.of("A", "B", "C", "D")) {
			for (int number = 1; number <= 12; number++) {
				stalls.addSeat(row, number);
			}
		}

		SeatSection balcony = venue.addSection("Balcony", 2);
		for (String row : List.of("E", "F")) {
			for (int number = 1; number <= 10; number++) {
				balcony.addSeat(row, number);
			}
		}

		return venue;
	}

	/** Stalls at Rs 1,200, balcony at Rs 600 -- held as paise, never as rupees. */
	private List<EventSeat> priceSeats(Event event, Venue venue) {
		List<EventSeat> priced = new ArrayList<>();
		for (SeatSection section : venue.getSections()) {
			long priceMinor = section.getName().equals("Stalls") ? 120_000L : 60_000L;
			for (Seat seat : section.getSeats()) {
				priced.add(new EventSeat(event, seat, priceMinor));
			}
		}
		return priced;
	}

}
