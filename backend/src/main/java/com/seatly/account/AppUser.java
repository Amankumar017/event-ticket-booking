package com.seatly.account;

import com.seatly.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Somebody who can sign in.
 */
@Entity
@Table(name = "app_user")
public class AppUser extends BaseEntity {

	@Column(name = "email", nullable = false, length = 160)
	private String email;

	/**
	 * A BCrypt hash, never a password.
	 * <p>
	 * Excluded from every view and DTO in the project. The only code that reads
	 * it hands it straight to the password encoder for comparison.
	 */
	@Column(name = "password_hash", nullable = false, length = 100)
	private String passwordHash;

	@Column(name = "display_name", nullable = false, length = 120)
	private String displayName;

	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 20)
	private Role role = Role.CUSTOMER;

	@Column(name = "enabled", nullable = false)
	private boolean enabled = true;

	/** Required by JPA. */
	protected AppUser() {
	}

	public AppUser(String email, String passwordHash, String displayName, Role role) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.displayName = displayName;
		this.role = role;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getDisplayName() {
		return displayName;
	}

	public Role getRole() {
		return role;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void disable() {
		this.enabled = false;
	}

}
