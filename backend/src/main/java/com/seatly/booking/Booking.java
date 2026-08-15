package com.seatly.booking;

import com.seatly.common.BaseEntity;
import com.seatly.event.Event;
import com.seatly.event.EventSeat;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One customer's claim on a set of seats for an event.
 * <p>
 * A booking is created PENDING with an expiry, and only becomes CONFIRMED once
 * payment lands. The lines are cascaded because they have no life of their own,
 * and a booking is small by nature -- nobody buys ten thousand seats at once.
 */
@Entity
@Table(name = "booking")
public class Booking extends BaseEntity {

	/** Short public identifier. The primary key stays internal. */
	@Column(name = "reference", nullable = false, unique = true, length = 16)
	private String reference;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "event_id", nullable = false)
	private Event event;

	@Column(name = "customer_name", nullable = false, length = 120)
	private String customerName;

	@Column(name = "customer_email", nullable = false, length = 160)
	private String customerEmail;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private BookingStatus status = BookingStatus.PENDING;

	@Column(name = "total_minor", nullable = false)
	private long totalMinor;

	@Column(name = "currency", nullable = false, length = 3)
	private String currency = "INR";

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "confirmed_at")
	private Instant confirmedAt;

	@OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<BookingSeat> lines = new ArrayList<>();

	/** Required by JPA. */
	protected Booking() {
	}

	public Booking(String reference, Event event, String customerName, String customerEmail, Instant expiresAt) {
		this.reference = reference;
		this.event = event;
		this.customerName = customerName;
		this.customerEmail = customerEmail;
		this.expiresAt = expiresAt;
	}

	/**
	 * Adds a seat and keeps the total in step with it. The price is copied onto
	 * the line rather than read through to the seat: what the customer was
	 * charged must not change later because somebody repriced the event.
	 */
	public BookingSeat addSeat(EventSeat eventSeat) {
		BookingSeat line = new BookingSeat(this, eventSeat, eventSeat.getPriceMinor());
		lines.add(line);
		totalMinor += eventSeat.getPriceMinor();
		return line;
	}

	public void confirm(Instant moment) {
		this.status = BookingStatus.CONFIRMED;
		this.confirmedAt = moment;
		this.expiresAt = null;
	}

	public void expire() {
		this.status = BookingStatus.EXPIRED;
	}

	public void cancel() {
		this.status = BookingStatus.CANCELLED;
	}

	public boolean hasLapsedBy(Instant moment) {
		return status == BookingStatus.PENDING && expiresAt != null && !expiresAt.isAfter(moment);
	}

	public String getReference() {
		return reference;
	}

	public Event getEvent() {
		return event;
	}

	public String getCustomerName() {
		return customerName;
	}

	public String getCustomerEmail() {
		return customerEmail;
	}

	public BookingStatus getStatus() {
		return status;
	}

	public long getTotalMinor() {
		return totalMinor;
	}

	public String getCurrency() {
		return currency;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getConfirmedAt() {
		return confirmedAt;
	}

	public List<BookingSeat> getLines() {
		return Collections.unmodifiableList(lines);
	}

}
