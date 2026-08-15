package com.seatly.event.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Hears seat changes from any instance and hands them to the local browsers.
 * <p>
 * Every instance subscribes, including the one that published, so there is a
 * single path from a committed change to a connected browser rather than one
 * path for local changes and another for remote ones.
 */
@Component
public class SeatUpdateSubscriber implements MessageListener {

	private static final Logger log = LoggerFactory.getLogger(SeatUpdateSubscriber.class);

	private final SeatStreamRegistry registry;
	private final ObjectMapper json;

	public SeatUpdateSubscriber(SeatStreamRegistry registry, ObjectMapper json) {
		this.registry = registry;
		this.json = json;
	}

	@Override
	public void onMessage(Message message, byte[] pattern) {
		try {
			registry.broadcast(json.readValue(
					new String(message.getBody(), StandardCharsets.UTF_8), SeatChanged.class));
		}
		catch (Exception unreadable) {
			// A message this instance cannot parse is not worth killing the
			// subscription over; an older instance may be publishing an older shape.
			log.warn("Ignoring an unreadable seat update", unreadable);
		}
	}

}
