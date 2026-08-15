package com.seatly.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

	Optional<EventSeat> findByEventIdAndSeatId(Long eventId, Long seatId);

	long countByEventIdAndStatus(Long eventId, EventSeatStatus status);

}
