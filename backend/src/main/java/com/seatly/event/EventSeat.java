package com.seatly.event;

import com.seatly.common.BaseEntity;
import com.seatly.venue.Seat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * A seat as sold for one event: its price, and its current claim.
 * <p>
 * This is the contended row. Every booking attempt in this system resolves to a
 * read and a write of exactly one {@code event_seat}, which is what makes the
 * concurrency question tractable -- there is a single place where two callers
 * can collide, and a single place to serialise them.
 * <p>
 * There is deliberately no {@code @Version} field yet. Optimistic locking
 * arrives in stage 5, after stage 4 has measured what goes wrong without it.
 */
@Entity
@Table(name = "event_seat")
public class EventSeat extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "event_id", nullable = false)
	private Event event;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "seat_id", nullable = false)
	private Seat seat;

	/** Minor units -- paise, not rupees. See the note in V2__domain.sql. */
	@Column(name = "price_minor", nullable = false)
	private long priceMinor;

	@Column(name = "currency", nullable = false, length = 3)
	private String currency = "INR";

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private EventSeatStatus status = EventSeatStatus.AVAILABLE;

	/** When the current hold lapses. Null unless {@code status} is HELD. */
	@Column(name = "held_until")
	private Instant heldUntil;

	/**
	 * Optimistic lock stamp.
	 * <p>
	 * Every update Hibernate writes carries {@code where version = ?} and bumps
	 * the value. A second transaction that read the row earlier finds its update
	 * matches nothing and fails rather than overwriting work it never saw.
	 * <p>
	 * The booking path does not rely on this -- it takes a real lock before it
	 * reads. This is the safety net for every other write path.
	 */
	@Version
	@Column(name = "version", nullable = false)
	private long version;

	/** Required by JPA. */
	protected EventSeat() {
	}

	public EventSeat(Event event, Seat seat, long priceMinor) {
		this.event = event;
		this.seat = seat;
		this.priceMinor = priceMinor;
	}

	/**
	 * Whether this seat can be claimed at the given moment.
	 * <p>
	 * A lapsed hold counts as claimable even before anything has tidied the row
	 * up: the deadline is the truth, and a reaper that has not run yet must not
	 * keep a seat off the market.
	 */
	public boolean isClaimableAt(Instant moment) {
		return switch (status) {
			case AVAILABLE -> true;
			case HELD -> heldUntil != null && !heldUntil.isAfter(moment);
			case SOLD, BLOCKED -> false;
		};
	}

	/**
	 * The status as a customer should see it at the given moment.
	 * <p>
	 * A hold that has run out reads as AVAILABLE even though the column still
	 * says HELD. The deadline is the truth; the row is tidied up later by a
	 * background job, and the seat map must not wait for it.
	 */
	public EventSeatStatus effectiveStatusAt(Instant moment) {
		return isClaimableAt(moment) ? EventSeatStatus.AVAILABLE : status;
	}

	public void holdUntil(Instant deadline) {
		this.status = EventSeatStatus.HELD;
		this.heldUntil = deadline;
	}

	public void markSold() {
		this.status = EventSeatStatus.SOLD;
		this.heldUntil = null;
	}

	public void release() {
		this.status = EventSeatStatus.AVAILABLE;
		this.heldUntil = null;
	}

	public Event getEvent() {
		return event;
	}

	public Seat getSeat() {
		return seat;
	}

	public long getPriceMinor() {
		return priceMinor;
	}

	public String getCurrency() {
		return currency;
	}

	public EventSeatStatus getStatus() {
		return status;
	}

	public Instant getHeldUntil() {
		return heldUntil;
	}

}
