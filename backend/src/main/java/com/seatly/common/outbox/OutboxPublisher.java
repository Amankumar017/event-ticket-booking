package com.seatly.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * Sends what the outbox is holding.
 *
 * <h2>Why the outbox exists</h2>
 *
 * Confirming a booking and emailing the ticket are two different systems, and
 * there is no transaction spanning both. Send inside the transaction and a
 * rollback leaves a customer holding a ticket for a booking that does not exist;
 * send after it commits and a process that dies in between loses the email with
 * nothing to show it was ever owed.
 * <p>
 * Writing the intent to the same transaction as the confirmation removes the
 * disagreement: either both happened or neither did. This job then delivers what
 * was written, and may safely try again, because a message that has already gone
 * out carries a {@code sent_at} and is never picked up twice.
 * <p>
 * This is at-least-once, not exactly-once. A crash between sending and recording
 * the send means one duplicate email -- which is why the thing on the other end
 * of a pattern like this should tolerate repeats.
 */
@Component
public class OutboxPublisher {

	private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

	private static final int MAX_ATTEMPTS = 5;
	private static final int BATCH_SIZE = 50;

	private final OutboxMessageRepository messages;
	private final MessageSender sender;
	private final Clock clock;

	public OutboxPublisher(OutboxMessageRepository messages, MessageSender sender, Clock clock) {
		this.messages = messages;
		this.sender = sender;
		this.clock = clock;
	}

	/**
	 * {@code @Transactional} belongs here, not only on {@link #publishPending}.
	 * <p>
	 * A method calling another method on the same object goes straight to it and
	 * never through the proxy that applies the annotation. Without this, the
	 * batch ran with no transaction at all: the entities were detached the moment
	 * each repository call returned, {@code markSent} changed an object nobody
	 * would ever flush, and every sweep sent the same email again while
	 * {@code sent_at} stayed null.
	 */
	@Scheduled(fixedDelayString = "${seatly.outbox.sweep-every:10s}")
	@Transactional
	public void sweep() {
		int sent = publishPending();
		if (sent > 0) {
			log.debug("Published {} outbox message(s)", sent);
		}
	}

	@Transactional
	public int publishPending() {
		List<OutboxMessage> pending = messages.findUnsent(MAX_ATTEMPTS, Limit.of(BATCH_SIZE));

		int sent = 0;
		for (OutboxMessage message : pending) {
			try {
				sender.send(message);
				message.markSent(clock.instant());
				sent++;
			}
			catch (RuntimeException failure) {
				// Recorded rather than rethrown: one unsendable message must not
				// stop the rest of the batch.
				message.markFailed(failure.getMessage());
				log.warn("Outbox message {} failed on attempt {}", message.getId(),
						message.getAttempts(), failure);
			}
		}
		return sent;
	}

}
