package com.seatly.common.outbox;

import com.seatly.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Something to send once the transaction that caused it has committed.
 */
@Entity
@Table(name = "outbox_message")
public class OutboxMessage extends BaseEntity {

	@Column(name = "message_type", nullable = false, length = 60)
	private String messageType;

	@Column(name = "recipient", nullable = false, length = 160)
	private String recipient;

	@Column(name = "payload", nullable = false, columnDefinition = "text")
	private String payload;

	@Column(name = "attempts", nullable = false)
	private int attempts;

	@Column(name = "sent_at")
	private Instant sentAt;

	@Column(name = "last_error", length = 300)
	private String lastError;

	/** Required by JPA. */
	protected OutboxMessage() {
	}

	public OutboxMessage(String messageType, String recipient, String payload) {
		this.messageType = messageType;
		this.recipient = recipient;
		this.payload = payload;
	}

	public void markSent(Instant moment) {
		this.sentAt = moment;
		this.attempts++;
		this.lastError = null;
	}

	public void markFailed(String error) {
		this.attempts++;
		this.lastError = error != null && error.length() > 300 ? error.substring(0, 300) : error;
	}

	public boolean isSent() {
		return sentAt != null;
	}

	public String getMessageType() {
		return messageType;
	}

	public String getRecipient() {
		return recipient;
	}

	public String getPayload() {
		return payload;
	}

	public int getAttempts() {
		return attempts;
	}

	public Instant getSentAt() {
		return sentAt;
	}

}
