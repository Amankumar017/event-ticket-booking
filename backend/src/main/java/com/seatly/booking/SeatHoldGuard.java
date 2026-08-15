package com.seatly.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A fast rejection for seats somebody is already holding.
 *
 * <h2>What this is not</h2>
 *
 * It is not where holds live. The database owns that, and it is the only thing
 * that decides whether a seat can be claimed -- see {@link BookingService},
 * which takes a row lock and re-checks regardless of what Redis said.
 * <p>
 * Two stores that both claim to know whether a seat is free is a distributed
 * consistency problem nobody needs. This one gets a strictly weaker job: turn
 * away callers who are provably too late, before they open a transaction and
 * queue on a row lock behind two hundred other people. When it says "taken" it
 * is right. When it says "go ahead" it is only guessing, and the database has
 * the final word.
 *
 * <h2>What happens when Redis is down</h2>
 *
 * Everything keeps working, more slowly. Every method fails open: a Redis error
 * is logged and treated as "no opinion", so the request proceeds to the
 * database exactly as it would have. Correctness never depends on this class
 * being available, which is the property that makes it safe to add.
 */
@Component
public class SeatHoldGuard {

	private static final Logger log = LoggerFactory.getLogger(SeatHoldGuard.class);

	private static final String KEY_PREFIX = "seatly:hold:";

	private final StringRedisTemplate redis;

	public SeatHoldGuard(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/**
	 * Claims every seat or none of them.
	 * <p>
	 * Partial success is released before returning, so a caller that loses on its
	 * third seat does not leave its first two looking taken for the whole TTL.
	 *
	 * @return true if the caller should go on and ask the database
	 */
	public boolean tryClaimAll(Collection<Long> eventSeatIds, Duration ttl) {
		List<Long> claimed = new ArrayList<>(eventSeatIds.size());
		try {
			for (Long seatId : eventSeatIds) {
				Boolean won = redis.opsForValue().setIfAbsent(key(seatId), "held", ttl);
				if (!Boolean.TRUE.equals(won)) {
					releaseAll(claimed);
					return false;
				}
				claimed.add(seatId);
			}
			return true;
		}
		catch (DataAccessException redisUnavailable) {
			log.warn("Seat hold guard unavailable, falling through to the database: {}",
					redisUnavailable.getMessage());
			releaseAll(claimed);
			return true;
		}
	}

	/**
	 * Gives the guard keys back.
	 * <p>
	 * Called when a hold is confirmed, cancelled or released early, and whenever
	 * the transaction that claimed them did not commit. Missing this would leave
	 * seats looking taken until the TTL ran out -- inconvenient rather than
	 * incorrect, since the database would still let the next caller through.
	 */
	public void releaseAll(Collection<Long> eventSeatIds) {
		if (eventSeatIds.isEmpty()) {
			return;
		}
		try {
			redis.delete(eventSeatIds.stream().map(this::key).toList());
		}
		catch (DataAccessException redisUnavailable) {
			log.warn("Could not release seat hold guards, they will expire on their own: {}",
					redisUnavailable.getMessage());
		}
	}

	private String key(Long eventSeatId) {
		return KEY_PREFIX + eventSeatId;
	}

}
