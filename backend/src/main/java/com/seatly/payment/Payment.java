package com.seatly.payment;

import com.seatly.booking.Booking;
import com.seatly.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One attempt to take money for a booking.
 */
@Entity
@Table(name = "payment")
public class Payment extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "booking_id", nullable = false)
	private Booking booking;

	/** How the provider refers to this payment, and how its webhooks name it. */
	@Column(name = "provider_reference", nullable = false, unique = true, length = 64)
	private String providerReference;

	@Column(name = "amount_minor", nullable = false)
	private long amountMinor;

	@Column(name = "currency", nullable = false, length = 3)
	private String currency;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private PaymentStatus status = PaymentStatus.REQUIRES_PAYMENT;

	@Column(name = "failure_reason", length = 200)
	private String failureReason;

	@Column(name = "settled_at")
	private Instant settledAt;

	/** Required by JPA. */
	protected Payment() {
	}

	public Payment(Booking booking, String providerReference) {
		this.booking = booking;
		this.providerReference = providerReference;
		// Taken from the booking, never from the client. A caller who could name
		// the amount could name a smaller one.
		this.amountMinor = booking.getTotalMinor();
		this.currency = booking.getCurrency();
	}

	public boolean isSettled() {
		return status != PaymentStatus.REQUIRES_PAYMENT;
	}

	public void succeedAt(Instant moment) {
		this.status = PaymentStatus.SUCCEEDED;
		this.settledAt = moment;
		this.failureReason = null;
	}

	public void failAt(Instant moment, String reason) {
		this.status = PaymentStatus.FAILED;
		this.settledAt = moment;
		this.failureReason = reason;
	}

	public Booking getBooking() {
		return booking;
	}

	public String getProviderReference() {
		return providerReference;
	}

	public long getAmountMinor() {
		return amountMinor;
	}

	public String getCurrency() {
		return currency;
	}

	public PaymentStatus getStatus() {
		return status;
	}

	public String getFailureReason() {
		return failureReason;
	}

	public Instant getSettledAt() {
		return settledAt;
	}

}
