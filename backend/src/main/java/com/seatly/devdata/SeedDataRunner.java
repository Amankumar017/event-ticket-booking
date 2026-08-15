package com.seatly.devdata;

import com.seatly.account.AppUser;
import com.seatly.account.AppUserRepository;
import com.seatly.account.Role;
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
import org.springframework.security.crypto.password.PasswordEncoder;
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

	private static final String DEMO_PASSWORD = "seatly-demo-pass";

	private final VenueRepository venues;
	private final EventRepository events;
	private final EventSeatRepository eventSeats;
	private final AppUserRepository users;
	private final PasswordEncoder passwords;

	public SeedDataRunner(VenueRepository venues, EventRepository events, EventSeatRepository eventSeats,
			AppUserRepository users, PasswordEncoder passwords) {
		this.venues = venues;
		this.events = events;
		this.eventSeats = eventSeats;
		this.users = users;
		this.passwords = passwords;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		// Each step checks for itself rather than one guard covering the lot.
		// A single early return meant that adding accounts to this method left
		// them uncreated on every database that already had the venue.
		seedAccounts();

		if (venues.existsByName(VENUE_NAME)) {
			log.info("Venue and event already present, leaving them alone");
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

	/**
	 * Two accounts to sign in with locally.
	 * <p>
	 * The password is in the source because this only ever runs under the
	 * {@code seed} profile against a throwaway local database. Nothing here is
	 * reachable from a deployment.
	 */
	private void seedAccounts() {
		int created = createAccount("customer@example.com", "Aman Kumar", Role.CUSTOMER)
				+ createAccount("organiser@example.com", "Prithvi Playhouse", Role.ORGANIZER);

		if (created > 0) {
			log.info("Seeded {} account(s): customer@example.com / organiser@example.com "
					+ "(password: {})", created, DEMO_PASSWORD);
		}
	}

	private int createAccount(String email, String displayName, Role role) {
		if (users.existsByEmail(email)) {
			return 0;
		}
		users.save(new AppUser(email, passwords.encode(DEMO_PASSWORD), displayName, role));
		return 1;
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
