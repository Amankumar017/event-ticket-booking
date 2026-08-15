package com.seatly.event;

import com.seatly.common.BaseEntity;
import com.seatly.venue.Venue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A performance at a venue, with a window during which its seats can be sold.
 * <p>
 * Note the absence of a {@code List<EventSeat>} here. A sold-out arena event has
 * tens of thousands of seats, and mapping that as a collection invites code to
 * load every one of them to answer a question about a single seat. Seats are
 * reached through {@link EventSeatRepository} instead, one query at a time,
 * fetching only what is needed.
 */
@Entity
@Table(name = "event")
public class Event extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "venue_id", nullable = false)
	private Venue venue;

	@Column(name = "title", nullable = false, length = 200)
	private String title;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "sales_open_at", nullable = false)
	private Instant salesOpenAt;

	@Column(name = "sales_close_at", nullable = false)
	private Instant salesCloseAt;

	/**
	 * STRING, never ORDINAL. Ordinal storage writes the enum's position, so
	 * inserting a new constant in the middle of the enum silently reinterprets
	 * every existing row: a data corruption bug with no error message.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private EventStatus status = EventStatus.DRAFT;

	/** Required by JPA. */
	protected Event() {
	}

	public Event(Venue venue, String title, Instant startsAt, Instant salesOpenAt, Instant salesCloseAt) {
		this.venue = venue;
		this.title = title;
		this.startsAt = startsAt;
		this.salesOpenAt = salesOpenAt;
		this.salesCloseAt = salesCloseAt;
	}

	/** True when the event is on sale and the clock is inside its sales window. */
	public boolean isOnSaleAt(Instant moment) {
		return status == EventStatus.ON_SALE
				&& !moment.isBefore(salesOpenAt)
				&& moment.isBefore(salesCloseAt);
	}

	public void openSales() {
		this.status = EventStatus.ON_SALE;
	}

	public void closeSales() {
		this.status = EventStatus.CLOSED;
	}

	public Venue getVenue() {
		return venue;
	}

	public String getTitle() {
		return title;
	}

	public Instant getStartsAt() {
		return startsAt;
	}

	public Instant getSalesOpenAt() {
		return salesOpenAt;
	}

	public Instant getSalesCloseAt() {
		return salesCloseAt;
	}

	public EventStatus getStatus() {
		return status;
	}

}
