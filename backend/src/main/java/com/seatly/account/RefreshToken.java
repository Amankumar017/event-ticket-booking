package com.seatly.account;

import com.seatly.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One issued refresh token, stored as a hash.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private AppUser user;

	@Column(name = "token_hash", nullable = false, unique = true, length = 64)
	private String tokenHash;

	/** Shared by every token descended from one login. */
	@Column(name = "family_id", nullable = false)
	private UUID familyId;

	@Column(name = "issued_at", nullable = false, insertable = false, updatable = false)
	private Instant issuedAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	/** Required by JPA. */
	protected RefreshToken() {
	}

	public RefreshToken(AppUser user, String tokenHash, UUID familyId, Instant expiresAt) {
		this.user = user;
		this.tokenHash = tokenHash;
		this.familyId = familyId;
		this.expiresAt = expiresAt;
	}

	public boolean isUsableAt(Instant moment) {
		return revokedAt == null && expiresAt.isAfter(moment);
	}

	public boolean isRevoked() {
		return revokedAt != null;
	}

	public void revokeAt(Instant moment) {
		if (revokedAt == null) {
			this.revokedAt = moment;
		}
	}

	public AppUser getUser() {
		return user;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public UUID getFamilyId() {
		return familyId;
	}

	public Instant getIssuedAt() {
		return issuedAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

}
