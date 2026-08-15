package com.seatly.event.stream;

import com.seatly.event.EventSeatStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry on its own: who hears what, and what happens to connections that
 * have gone away.
 */
class SeatStreamRegistryTests {

	private SeatStreamRegistry registry;

	@BeforeEach
	void setUp() {
		registry = new SeatStreamRegistry();
	}

	/**
	 * Removal on completion and timeout is wired to callbacks the servlet
	 * container fires, and there is no container here -- calling
	 * {@code complete()} on a bare emitter does nothing at all. The two paths
	 * below cover what actually happens in practice: a browser that goes away is
	 * noticed the next time something is sent to it.
	 */
	@Test
	void watchingRegistersTheBrowser() {
		registry.watch(7L);

		assertThat(registry.watcherCount(7L)).isEqualTo(1);
		assertThat(registry.watcherCount(9L)).isZero();
	}

	@Test
	void aChangeOnlyReachesWatchersOfThatEvent() {
		AtomicInteger toSeven = new AtomicInteger();
		AtomicInteger toNine = new AtomicInteger();
		registry.register(7L, new CountingEmitter(toSeven));
		registry.register(9L, new CountingEmitter(toNine));

		registry.broadcast(new SeatChanged(7L, 1L, EventSeatStatus.HELD, Instant.now()));

		assertThat(toSeven.get()).isEqualTo(1);
		assertThat(toNine.get()).isZero();
	}

	/**
	 * A browser that has gone is discovered when a send fails, and dropped there
	 * and then. A registry that only forgets on request grows forever.
	 */
	@Test
	void anEmitterThatFailsToSendIsDropped() {
		registry.register(7L, new CountingEmitter(new AtomicInteger()));
		registry.register(7L, new BrokenEmitter());
		assertThat(registry.watcherCount(7L)).isEqualTo(2);

		registry.broadcast(new SeatChanged(7L, 1L, EventSeatStatus.SOLD, null));

		assertThat(registry.watcherCount(7L)).isEqualTo(1);
	}

	/** The heartbeat is also how a dead connection gets noticed while idle. */
	@Test
	void theHeartbeatDropsConnectionsThatHaveDied() {
		registry.register(7L, new BrokenEmitter());

		registry.heartbeat();

		assertThat(registry.watcherCount(7L)).isZero();
	}

	@Test
	void broadcastingToAnEventNobodyIsWatchingIsHarmless() {
		registry.broadcast(new SeatChanged(123L, 1L, EventSeatStatus.AVAILABLE, null));

		assertThat(registry.watcherCount(123L)).isZero();
	}

	/** Counts what it is sent, instead of writing to a response that is not there. */
	private static final class CountingEmitter extends SseEmitter {

		private final AtomicInteger counter;

		private CountingEmitter(AtomicInteger counter) {
			this.counter = counter;
		}

		@Override
		public void send(SseEventBuilder builder) {
			counter.incrementAndGet();
		}
	}

	/** Stands in for a browser that has closed the tab. */
	private static final class BrokenEmitter extends SseEmitter {

		@Override
		public void send(SseEventBuilder builder) throws IOException {
			throw new IOException("client gone");
		}
	}

}
