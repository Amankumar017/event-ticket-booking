package com.seatly.venue;

import com.seatly.common.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A named block of a venue: Stalls, Balcony, Box.
 */
@Entity
@Table(name = "seat_section")
public class SeatSection extends BaseEntity {

	/**
	 * Explicitly LAZY. JPA defaults {@code @ManyToOne} to EAGER, which quietly
	 * turns one query into several and is the usual source of a mysteriously slow
	 * list endpoint.
	 */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "venue_id", nullable = false)
	private Venue venue;

	@Column(name = "name", nullable = false, length = 80)
	private String name;

	@Column(name = "display_order", nullable = false)
	private int displayOrder;

	@OneToMany(mappedBy = "section", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<Seat> seats = new ArrayList<>();

	/** Required by JPA. */
	protected SeatSection() {
	}

	SeatSection(Venue venue, String name, int displayOrder) {
		this.venue = venue;
		this.name = name;
		this.displayOrder = displayOrder;
	}

	public Seat addSeat(String rowLabel, int seatNumber) {
		Seat seat = new Seat(this, rowLabel, seatNumber);
		seats.add(seat);
		return seat;
	}

	public Venue getVenue() {
		return venue;
	}

	public String getName() {
		return name;
	}

	public int getDisplayOrder() {
		return displayOrder;
	}

	public List<Seat> getSeats() {
		return Collections.unmodifiableList(seats);
	}

}
