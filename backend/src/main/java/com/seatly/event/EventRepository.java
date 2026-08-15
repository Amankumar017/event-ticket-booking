package com.seatly.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {

	/**
	 * Events a customer can currently buy into.
	 * <p>
	 * The venue is joined in up front. Without it, rendering a list of ten events
	 * costs eleven queries -- one for the events, then one per venue as each lazy
	 * proxy is touched. That is the N+1 problem, and it is invisible until the
	 * list gets long.
	 */
	@Query("""
			select e from Event e
			join fetch e.venue
			where e.status = com.seatly.event.EventStatus.ON_SALE
			  and e.salesOpenAt <= :moment
			  and e.salesCloseAt > :moment
			order by e.startsAt asc
			""")
	List<Event> findOnSaleAt(Instant moment);

}
