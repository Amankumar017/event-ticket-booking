package com.seatly.booking;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

	Optional<Booking> findByReference(String reference);

	List<Booking> findByEventIdOrderByIdAsc(Long eventId);

	List<Booking> findByUserIdOrderByIdDesc(Long userId);

	/**
	 * Holds that ran out of time, oldest first.
	 * <p>
	 * Ids rather than entities, and limited rather than exhaustive: the job that
	 * consumes this takes each booking in its own transaction, so a large backlog
	 * is released in bounded pieces instead of one enormous locking transaction.
	 */
	@Query("""
			select b.id from Booking b
			where b.status = com.seatly.booking.BookingStatus.PENDING
			  and b.expiresAt <= :moment
			order by b.expiresAt asc
			""")
	List<Long> findLapsedIds(Instant moment, Limit limit);

}
