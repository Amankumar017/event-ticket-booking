package com.seatly.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stands in for a real mail provider by writing the message to the log.
 * <p>
 * The point of this stage is the outbox, not the email. Swapping this for
 * something that talks to a mail service is one class and no changes anywhere
 * else, which is rather the point of the interface.
 * <p>
 * Plainly registered rather than conditional: {@code @ConditionalOnMissingBean}
 * is evaluated for {@code @Bean} methods in configuration classes, and on a
 * component-scanned class like this one it quietly registers nothing at all.
 */
@Component
public class LoggingMessageSender implements MessageSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingMessageSender.class);

	@Override
	public void send(OutboxMessage message) {
		log.info("Sending {} to {}: {}",
				message.getMessageType(), message.getRecipient(), message.getPayload());
	}

}
