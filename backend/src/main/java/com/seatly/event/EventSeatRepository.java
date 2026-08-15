package com.seatly.event;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EventSeatRepository extends JpaRepository<EventSeat, Long> {

	/**
	 * Every seat for an event, ordered as the hall is laid out.
	 * <p>
	 * Seat and section are fetched with the seats because the map cannot be drawn
	 * without them; leaving them lazy would turn one query into one per seat.
	 */
	@Query("""
			select es from EventSeat es
			join fetch es.seat s
			join fetch s.section sec
			where es.event.id = :eventId
			order by sec.displayOrder asc, s.rowLabel asc, s.seatNumber asc
			""")
	List<EventSeat> findSeatMap(Long eventId);

	/**
	 * How many seats are still buyable, for several events at once.
	 * <p>
	 * One grouped query rather than one count per event: asking per event turns a
	 * ten-row listing into eleven round trips. The condition matches the rule the
	 * seat map uses: a hold whose deadline has passed is buyable again, whether
	 * or not anything has tidied the row up yet.
	 */
	@Query("""
			select new com.seatly.event.AvailableSeatCount(es.event.id, count(es))
			from EventSeat es
			where es.event.id in :eventIds
			  and (es.status = com.seatly.event.EventSeatStatus.AVAILABLE
			       or (es.status = com.seatly.event.EventSeatStatus.HELD and es.heldUntil <= :moment))
			group by es.event.id
			""")
	List<AvailableSeatCount> countAvailableForEvents(Collection<Long> eventIds, Instant moment);

	/**
	 * Reads seats with a write lock held until the transaction ends.
	 * <p>
	 * Everything that makes the booking path correct comes from this: the lock is
	 * taken before the availability check runs, so no second caller can read the
	 * row between the check and the write. A competing transaction blocks here,
	 * and by the time it is let through the seat is already sold, which it sees.
	 * <p>
	 * Hibernate's PostgreSQL dialect renders {@code PESSIMISTIC_WRITE} as
	 * {@code for no key update}, not {@code for update}, and the difference
	 * matters. {@code for no key update} still excludes other bookers, but it
	 * permits the {@code for key share} locks that foreign keys take, so the
	 * {@code booking_seat} insert pointing back at this row no longer contends
	 * with it. That is exactly the cycle that deadlocked the unlocked version.
	 * <p>
	 * The {@code order by} is not cosmetic. Two bookings for the overlapping sets
	 * {A, B} and {B, A} would take their locks in opposite orders and deadlock.
	 * Locking in a fixed order, any fixed order at all as long as everyone agrees on
	 * it, makes that impossible.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select es from EventSeat es where es.id in :ids order by es.id asc")
	List<EventSeat> lockAllById(Collection<Long> ids);

	Optional<EventSeat> findByEventIdAndSeatId(Long eventId, Long seatId);

	long countByEventIdAndStatus(Long eventId, EventSeatStatus status);

}
