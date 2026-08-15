package com.seatly.event.stream;

import com.seatly.common.NotFoundException;
import com.seatly.event.EventRepository;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/events")
public class SeatStreamController {

	private final SeatStreamRegistry registry;
	private final EventRepository events;

	public SeatStreamController(SeatStreamRegistry registry, EventRepository events) {
		this.registry = registry;
		this.events = events;
	}

	/**
	 * A live feed of seat changes for one event.
	 *
	 * <h2>Server-sent events, not WebSockets</h2>
	 *
	 * The traffic here goes one way: the server tells browsers what changed, and
	 * browsers never reply on this channel. SSE is that, over an ordinary HTTP
	 * response, with reconnection handled by the browser rather than by code
	 * somebody has to write and get right. A WebSocket would add a second
	 * protocol, its own upgrade path through every proxy, and a reconnect loop,
	 * in exchange for a direction nothing needs.
	 *
	 * <h2>Open, like the chart itself</h2>
	 *
	 * Anybody may watch a seating chart, so this needs no account. It carries
	 * nothing an anonymous visitor could not already see by reloading the page --
	 * seat ids and statuses, no customer, no booking reference.
	 */
	@GetMapping(path = "/{eventId}/seats/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream(@PathVariable Long eventId) {
		if (!events.existsById(eventId)) {
			throw NotFoundException.of("Event", eventId);
		}
		return registry.watch(eventId);
	}

}
