package com.seatly.booking;

import com.seatly.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * The guard against real Redis, plus the one behaviour that matters most: what
 * it does when Redis is not there.
 */
class SeatHoldGuardTests extends IntegrationTest {

	private static final Duration TTL = Duration.ofMinutes(5);

	@Autowired
	private SeatHoldGuard guard;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private com.seatly.common.metrics.SeatlyMetrics metrics;

	@BeforeEach
	void clearGuardKeys() {
		Set<String> keys = redis.keys("seatly:hold:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	@Test
	void theFirstCallerClaimsTheSeatAndTheSecondIsTurnedAway() {
		assertThat(guard.tryClaimAll(List.of(1L), TTL)).isTrue();
		assertThat(guard.tryClaimAll(List.of(1L), TTL)).isFalse();
	}

	@Test
	void releasingPutsTheSeatBack() {
		guard.tryClaimAll(List.of(1L), TTL);
		guard.releaseAll(List.of(1L));

		assertThat(guard.tryClaimAll(List.of(1L), TTL)).isTrue();
	}

	@Test
	void aClaimCarriesTheHoldDeadlineAsItsTimeToLive() {
		guard.tryClaimAll(List.of(42L), TTL);

		Long secondsLeft = redis.getExpire("seatly:hold:42");

		assertThat(secondsLeft).isBetween(1L, TTL.toSeconds());
	}

	/**
	 * All or nothing. A caller that loses on its third seat must not leave the
	 * first two looking taken for the next five minutes.
	 */
	@Test
	void losingOneSeatReleasesTheOnesAlreadyClaimed() {
		guard.tryClaimAll(List.of(3L), TTL);

		assertThat(guard.tryClaimAll(List.of(1L, 2L, 3L), TTL)).isFalse();

		assertThat(redis.hasKey("seatly:hold:1")).isFalse();
		assertThat(redis.hasKey("seatly:hold:2")).isFalse();
	}

	/**
	 * The property the whole design rests on: when Redis is unreachable the guard
	 * has no opinion, and the caller carries on to the database.
	 */
	@Test
	void whenRedisIsUnreachableTheGuardWavesEverybodyThrough() {
		StringRedisTemplate broken = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		given(broken.opsForValue()).willReturn(values);
		given(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
				.willThrow(new QueryTimeoutException("Redis is not answering"));

		SeatHoldGuard unreachable = new SeatHoldGuard(broken, metrics);

		assertThat(unreachable.tryClaimAll(List.of(1L, 2L), TTL)).isTrue();
	}

	@Test
	void releasingSurvivesRedisBeingUnreachable() {
		StringRedisTemplate broken = mock(StringRedisTemplate.class);
		given(broken.delete(any(List.class))).willThrow(new QueryTimeoutException("nope"));

		SeatHoldGuard unreachable = new SeatHoldGuard(broken, metrics);

		// No exception escapes: the keys expire on their own soon enough.
		unreachable.releaseAll(List.of(1L));
	}

}
