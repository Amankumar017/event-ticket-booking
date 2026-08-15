package com.seatly.domain;

import com.seatly.booking.Booking;
import com.seatly.booking.BookingRepository;
import com.seatly.event.Event;
import com.seatly.event.EventRepository;
import com.seatly.event.EventSeat;
import com.seatly.event.EventSeatRepository;
import com.seatly.event.EventSeatStatus;
import com.seatly.support.IntegrationTest;
import com.seatly.support.TestAccounts;
import com.seatly.venue.Seat;
import com.seatly.venue.SeatSection;
import com.seatly.venue.Venue;
import com.seatly.venue.VenueRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks that the object model and the migrated schema actually agree -- on
 * cascades, on fetch plans, on how enums are stored, and on which invariants the
 * database refuses to let through.
 * <p>
 * Transactional, so each test rolls back and the shared container stays clean.
 */
@Transactional
class DomainMappingTests extends IntegrationTest {

	@Autowired
	private VenueRepository venues;

	@Autowired
	private EventRepository events;

	@Autowired
	private EventSeatRepository eventSeats;

	@Autowired
	private BookingRepository bookings;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TestAccounts accounts;

	@Test
	void savingAVenueCascadesItsSectionsAndSeats() {
		Venue venue = venues.save(smallVenue());
		flushAndClear();

		Venue reloaded = venues.findById(venue.getId()).orElseThrow();

		assertThat(reloaded.getSections()).hasSize(2);
		assertThat(reloaded.getSections().get(0).getSeats()).hasSize(3);
		assertThat(reloaded.getSections().get(0).getName()).isEqualTo("Stalls");
	}

	@Test
	void theDatabaseOwnsTheAuditTimestamps() {
		Venue venue = venues.saveAndFlush(smallVenue());

		// Never set in Java: these come back from the column defaults.
		assertThat(venue.getCreatedAt()).isNotNull();
		assertThat(venue.getUpdatedAt()).isNotNull();
	}

	@Test
	void theSeatMapComesBackInHallOrder() {
		Event event = onSaleEvent();
		priceEverySeat(event);
		flushAndClear();

		List<EventSeat> map = eventSeats.findSeatMap(event.getId());

		assertThat(map).hasSize(5);
		assertThat(map).extracting(seat -> seat.getSeat().label())
				.containsExactly("A1", "A2", "A3", "E1", "E2");
		// Stalls before Balcony, because the section's display order says so.
		assertThat(map.get(0).getSeat().getSection().getName()).isEqualTo("Stalls");
	}

	@Test
	void enumsAreStoredByNameNotByPosition() {
		Event event = onSaleEvent();
		flushAndClear();

		String stored = jdbc.queryForObject(
				"select status from event where id = ?", String.class, event.getId());

		assertThat(stored).isEqualTo("ON_SALE");
	}

	@Test
	void aSeatCarriesItsOwnStateForEachEvent() {
		Event event = onSaleEvent();
		priceEverySeat(event);
		flushAndClear();

		assertThat(eventSeats.countByEventIdAndStatus(event.getId(), EventSeatStatus.AVAILABLE))
				.isEqualTo(5);
	}

	@Test
	void aBookingKeepsItsTotalInStepWithItsSeats() {
		Event event = onSaleEvent();
		priceEverySeat(event);
		List<EventSeat> map = eventSeats.findSeatMap(event.getId());

		String reference = "BK-" + UUID.randomUUID().toString().substring(0, 8);
		Booking booking = new Booking(reference, event, accounts.customer(),
				Instant.now().plus(Duration.ofMinutes(5)));
		booking.addSeat(map.get(0));
		booking.addSeat(map.get(4));
		bookings.save(booking);
		flushAndClear();

		Booking reloaded = bookings.findByReference(reference).orElseThrow();

		assertThat(reloaded.getLines()).hasSize(2);
		// One stall at Rs 1,200 plus one balcony seat at Rs 600, in paise.
		assertThat(reloaded.getTotalMinor()).isEqualTo(180_000L);
	}

	@Test
	void theSameSeatCannotBeListedTwiceForOneEvent() {
		Event event = onSaleEvent();
		Seat firstSeat = event.getVenue().getSections().get(0).getSeats().get(0);
		eventSeats.saveAndFlush(new EventSeat(event, firstSeat, 120_000L));

		assertThatThrownBy(() -> eventSeats.saveAndFlush(new EventSeat(event, firstSeat, 120_000L)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void aHeldSeatMustCarryADeadline() {
		Event event = onSaleEvent();
		Seat firstSeat = event.getVenue().getSections().get(0).getSeats().get(0);
		EventSeat seat = eventSeats.saveAndFlush(new EventSeat(event, firstSeat, 120_000L));

		// Straight to the database: the check constraint has to hold even when the
		// write does not come through the domain model.
		assertThatThrownBy(() -> jdbc.update(
				"update event_seat set status = 'HELD', held_until = null where id = ?", seat.getId()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private Venue smallVenue() {
		Venue venue = new Venue("Test Hall " + UUID.randomUUID(), "Mumbai");

		SeatSection stalls = venue.addSection("Stalls", 1);
		stalls.addSeat("A", 1);
		stalls.addSeat("A", 2);
		stalls.addSeat("A", 3);

		SeatSection balcony = venue.addSection("Balcony", 2);
		balcony.addSeat("E", 1);
		balcony.addSeat("E", 2);

		return venue;
	}

	private Event onSaleEvent() {
		Venue venue = venues.save(smallVenue());
		Instant now = Instant.now();
		Event event = new Event(venue, "Test Performance",
				now.plus(Duration.ofDays(7)),
				now.minus(Duration.ofHours(1)),
				now.plus(Duration.ofDays(6)));
		event.openSales();
		return events.save(event);
	}

	private void priceEverySeat(Event event) {
		for (SeatSection section : event.getVenue().getSections()) {
			long priceMinor = section.getName().equals("Stalls") ? 120_000L : 60_000L;
			for (Seat seat : section.getSeats()) {
				eventSeats.save(new EventSeat(event, seat, priceMinor));
			}
		}
	}

	private void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}

}
