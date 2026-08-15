package com.seatly.event.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Puts committed seat changes onto Redis, where every instance can hear them.
 *
 * <h2>{@code AFTER_COMMIT}, and why it is the whole point</h2>
 *
 * An ordinary {@code @EventListener} would run while the transaction is still
 * open, and would happily tell every browser a seat was taken by a transaction
 * that went on to roll back. There is no unsending an SSE message. Waiting for
 * the commit means the only changes anybody hears about are the ones that
 * actually happened.
 *
 * <h2>Redis, because emitters cannot be shared</h2>
 *
 * A live SSE connection belongs to one JVM. With two instances behind a load
 * balancer, a customer connected to the first would never hear about a seat
 * sold on the second. Publishing to a channel both subscribe to fixes that
 * without either instance knowing the other exists.
 * <p>
 * Delivery is best-effort by design: Redis pub/sub drops messages nobody is
 * listening for, and a browser that reconnects re-reads the chart anyway. This
 * makes the seat map current, it is not a record of anything.
 */
@Component
public class SeatUpdateBroadcaster {

	private static final Logger log = LoggerFactory.getLogger(SeatUpdateBroadcaster.class);

	public static final String CHANNEL = "seatly:seat-updates";

	private final StringRedisTemplate redis;
	private final ObjectMapper json;

	public SeatUpdateBroadcaster(StringRedisTemplate redis, ObjectMapper json) {
		this.redis = redis;
		this.json = json;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void publish(SeatChanged change) {
		try {
			redis.convertAndSend(CHANNEL, json.writeValueAsString(change));
		}
		catch (Exception unavailable) {
			// Swallowed on purpose. This runs after the commit, so throwing would
			// not undo the booking; it would only turn a successful sale into an
			// error the customer sees. A missed update costs a stale seat map
			// until the next reload.
			log.warn("Could not broadcast a seat update: {}", unavailable.getMessage());
		}
	}

}
