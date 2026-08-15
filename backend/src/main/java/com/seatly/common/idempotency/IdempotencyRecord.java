package com.seatly.common.idempotency;

import com.seatly.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A reply kept so that a retried request can be answered without doing the work
 * again.
 */
@Entity
@Table(name = "idempotency_key")
public class IdempotencyRecord extends BaseEntity {

	public enum State {
		/** Claimed, but the work has not finished yet. */
		IN_PROGRESS,
		/** Finished; {@code responseBody} is what the first caller received. */
		COMPLETED
	}

	@Column(name = "idempotency_key", nullable = false, length = 200)
	private String key;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "request_fingerprint", nullable = false, length = 64)
	private String requestFingerprint;

	@Enumerated(EnumType.STRING)
	@Column(name = "state", nullable = false, length = 20)
	private State state = State.IN_PROGRESS;

	@Column(name = "response_status")
	private Integer responseStatus;

	@Column(name = "response_body", columnDefinition = "text")
	private String responseBody;

	/** Required by JPA. */
	protected IdempotencyRecord() {
	}

	public IdempotencyRecord(String key, Long userId, String requestFingerprint) {
		this.key = key;
		this.userId = userId;
		this.requestFingerprint = requestFingerprint;
	}

	public void complete(int status, String body) {
		this.state = State.COMPLETED;
		this.responseStatus = status;
		this.responseBody = body;
	}

	public boolean isCompleted() {
		return state == State.COMPLETED;
	}

	public boolean matches(String fingerprint) {
		return requestFingerprint.equals(fingerprint);
	}

	public String getKey() {
		return key;
	}

	public Long getUserId() {
		return userId;
	}

	public Integer getResponseStatus() {
		return responseStatus;
	}

	public String getResponseBody() {
		return responseBody;
	}

}
