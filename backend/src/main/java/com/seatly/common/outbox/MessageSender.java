package com.seatly.common.outbox;

/**
 * Whatever actually delivers an outbox message.
 */
public interface MessageSender {

	void send(OutboxMessage message);

}
