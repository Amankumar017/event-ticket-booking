package com.seatly.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface BookingSeatRepository extends JpaRepository<BookingSeat, Long> {

	/**
	 * Seats that appear on more than one booking.
	 * <p>
	 * The audit query for the whole project. On a correct system it returns
	 * nothing, always -- so it doubles as the assertion that the concurrency
	 * tests make, and as the check worth running against production data.
	 */
	@Query("""
			select bs.eventSeat.id
			from BookingSeat bs
			group by bs.eventSeat.id
			having count(bs) > 1
			""")
	List<Long> findDoubleBookedSeatIds();

	long countByEventSeatId(Long eventSeatId);

	/**
	 * Live claims on any of these seats, with the bookings behind them.
	 * <p>
	 * Used to find the claim a lapsed hold left behind, so that whoever takes the
	 * seat next can retire it.
	 */
	@Query("""
			select bs from BookingSeat bs
			join fetch bs.booking
			where bs.eventSeat.id in :eventSeatIds
			  and bs.active = true
			""")
	List<BookingSeat> findLiveClaims(Collection<Long> eventSeatIds);

}
