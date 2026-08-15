package com.seatly.event;

import org.springframework.data.jpa.repository.JpaRepository;
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
	 * seat map uses -- a hold whose deadline has passed is buyable again, whether
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

	Optional<EventSeat> findByEventIdAndSeatId(Long eventId, Long seatId);

	long countByEventIdAndStatus(Long eventId, EventSeatStatus status);

}
