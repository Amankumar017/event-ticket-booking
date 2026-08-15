package com.seatly.venue;

import com.seatly.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One physical seat, identified by its position within a section.
 * <p>
 * A seat carries no price and no availability: those belong to the seat as sold
 * for a particular event, which is {@code EventSeat}. Keeping them apart is what
 * lets the same hall run two events at once without the two interfering.
 */
@Entity
@Table(name = "seat")
public class Seat extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "section_id", nullable = false)
	private SeatSection section;

	@Column(name = "row_label", nullable = false, length = 8)
	private String rowLabel;

	@Column(name = "seat_number", nullable = false)
	private int seatNumber;

	/** Required by JPA. */
	protected Seat() {
	}

	Seat(SeatSection section, String rowLabel, int seatNumber) {
		this.section = section;
		this.rowLabel = rowLabel;
		this.seatNumber = seatNumber;
	}

	public SeatSection getSection() {
		return section;
	}

	public String getRowLabel() {
		return rowLabel;
	}

	public int getSeatNumber() {
		return seatNumber;
	}

	/** Human-readable position, e.g. {@code B12}. */
	public String label() {
		return rowLabel + seatNumber;
	}

}
