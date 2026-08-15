package com.seatly.booking;

import com.seatly.common.BaseEntity;
import com.seatly.event.EventSeat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One seat on one booking, at the price charged for it.
 */
@Entity
@Table(name = "booking_seat")
public class BookingSeat extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "booking_id", nullable = false)
	private Booking booking;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "event_seat_id", nullable = false)
	private EventSeat eventSeat;

	@Column(name = "price_minor", nullable = false)
	private long priceMinor;

	/** Required by JPA. */
	protected BookingSeat() {
	}

	BookingSeat(Booking booking, EventSeat eventSeat, long priceMinor) {
		this.booking = booking;
		this.eventSeat = eventSeat;
		this.priceMinor = priceMinor;
	}

	public Booking getBooking() {
		return booking;
	}

	public EventSeat getEventSeat() {
		return eventSeat;
	}

	public long getPriceMinor() {
		return priceMinor;
	}

}
