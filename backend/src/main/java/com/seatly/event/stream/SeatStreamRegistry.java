package com.seatly.event.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The browsers currently watching each seating chart, on this instance.
 *
 * <h2>Per instance, on purpose</h2>
 *
 * An {@link SseEmitter} is a live HTTP response held open by one JVM; it cannot
 * be handed to another. So this only ever knows about its own connections, and
 * {@link SeatUpdateSubscriber} is what makes a change on one instance reach the
 * browsers connected to all the others.
 *
 * <h2>Connections end badly more often than they end well</h2>
 *
 * People close tabs, walk into tunnels, and sit behind proxies with opinions
 * about idle sockets. Every emitter therefore cleans itself up on completion,
 * timeout and error, and any send that throws removes the emitter there and
 * then. A registry that only forgets connections when asked politely grows
 * until the heap runs out.
 */
@Component
public class SeatStreamRegistry {

	private static final Logger log = LoggerFactory.getLogger(SeatStreamRegistry.class);

	/** Long enough not to churn, short enough that a forgotten tab lets go. */
	static final long TIMEOUT_MILLIS = 30 * 60 * 1000L;

	private final Map<Long, Set<SseEmitter>> watchers = new ConcurrentHashMap<>();

	public SseEmitter watch(Long eventId) {
		SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
		register(eventId, emitter);

		try {
			// An immediate event, so the browser's connection is confirmed rather
			// than merely opened. EventSource reports a failure to connect only
			// once something arrives or the socket dies.
			emitter.send(SseEmitter.event().name("watching").data(Map.of("eventId", eventId)));
		}
		catch (IOException gone) {
			remove(eventId, emitter);
		}

		return emitter;
	}

	/** Starts tracking an emitter, and arranges for it to be forgotten. */
	void register(Long eventId, SseEmitter emitter) {
		watchers.computeIfAbsent(eventId, id -> new CopyOnWriteArraySet<>()).add(emitter);
		emitter.onCompletion(() -> remove(eventId, emitter));
		emitter.onTimeout(() -> remove(eventId, emitter));
		emitter.onError(failure -> remove(eventId, emitter));
	}

	/** Sends a change to everybody watching that event on this instance. */
	public void broadcast(SeatChanged change) {
		Set<SseEmitter> watching = watchers.get(change.eventId());
		if (watching == null || watching.isEmpty()) {
			return;
		}

		for (SseEmitter emitter : watching) {
			try {
				emitter.send(SseEmitter.event().name("seat").data(change));
			}
			catch (IOException | IllegalStateException gone) {
				// The browser went away between the last heartbeat and now.
				remove(change.eventId(), emitter);
			}
		}
	}

	/**
	 * Keeps idle connections alive.
	 * <p>
	 * A comment line, which {@code EventSource} ignores. Without it an idle
	 * stream looks indistinguishable from a dead one to anything between the
	 * browser and here, and proxies close what looks dead.
	 */
	@Scheduled(fixedDelayString = "${seatly.stream.heartbeat-every:20s}")
	public void heartbeat() {
		watchers.forEach((eventId, watching) -> {
			for (SseEmitter emitter : watching) {
				try {
					emitter.send(SseEmitter.event().comment("keep-alive"));
				}
				catch (IOException | IllegalStateException gone) {
					remove(eventId, emitter);
				}
			}
		});
	}

	public int watcherCount(Long eventId) {
		return watchers.getOrDefault(eventId, Set.of()).size();
	}

	private void remove(Long eventId, SseEmitter emitter) {
		Set<SseEmitter> watching = watchers.get(eventId);
		if (watching == null) {
			return;
		}
		watching.remove(emitter);
		// The empty set goes too. Otherwise the map keeps one entry per event ever
		// watched, for as long as the process lives. Removed by identity, so a set
		// somebody has just added a watcher to is left alone.
		if (watching.isEmpty()) {
			watchers.remove(eventId, watching);
		}
		log.debug("Stopped watching event {}; {} left", eventId, watcherCount(eventId));
	}

}
