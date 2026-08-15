package com.seatly.payment;

import com.seatly.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A webhook the provider has delivered, recorded by its own event id.
 */
@Entity
@Table(name = "webhook_event")
public class WebhookEvent extends BaseEntity {

	@Column(name = "provider_event_id", nullable = false, unique = true, length = 100)
	private String providerEventId;

	@Column(name = "event_type", nullable = false, length = 60)
	private String eventType;

	@Column(name = "payload", nullable = false, columnDefinition = "text")
	private String payload;

	@Column(name = "received_at", nullable = false, insertable = false, updatable = false)
	private Instant receivedAt;

	@Column(name = "processed_at")
	private Instant processedAt;

	/** Required by JPA. */
	protected WebhookEvent() {
	}

	public WebhookEvent(String providerEventId, String eventType, String payload) {
		this.providerEventId = providerEventId;
		this.eventType = eventType;
		this.payload = payload;
	}

	public void markProcessed(Instant moment) {
		this.processedAt = moment;
	}

	public String getProviderEventId() {
		return providerEventId;
	}

	public String getEventType() {
		return eventType;
	}

	public Instant getProcessedAt() {
		return processedAt;
	}

}
