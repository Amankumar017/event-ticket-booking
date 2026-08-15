package com.seatly.support;

import com.seatly.account.AppUser;
import com.seatly.account.AppUserRepository;
import com.seatly.account.Role;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Accounts for tests, and a way to act as one.
 *
 * <h2>Why a real token rather than {@code @WithMockUser}</h2>
 *
 * {@code @WithMockUser} produces a username-and-password authentication, and
 * this application reads identity from a JWT principal. A test signed in that
 * way would authenticate happily and then fail the moment anything asked which
 * account it was. The token built here has the same shape as the real one, so a
 * test exercises the same code path a request does.
 */
@Component
public class TestAccounts {

	public static final String PASSWORD = "correct-horse-battery";

	private final AppUserRepository users;
	private final PasswordEncoder passwords;

	public TestAccounts(AppUserRepository users, PasswordEncoder passwords) {
		this.users = users;
		this.passwords = passwords;
	}

	public AppUser customer() {
		return create("customer-" + UUID.randomUUID() + "@example.com", "Aman", Role.CUSTOMER);
	}

	public AppUser organiser() {
		return create("organiser-" + UUID.randomUUID() + "@example.com", "Prithvi", Role.ORGANIZER);
	}

	public AppUser create(String email, String displayName, Role role) {
		return users.save(new AppUser(email, passwords.encode(PASSWORD), displayName, role));
	}

	/**
	 * Acts as this account for the rest of the current thread.
	 * <p>
	 * Thread-bound, like the real security context. A test that spawns threads
	 * has to call this inside each one.
	 */
	public void actAs(AppUser user) {
		Jwt token = Jwt.withTokenValue("test-token")
				.header("alg", "HS256")
				.subject(String.valueOf(user.getId()))
				.claim("role", user.getRole().name())
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(900))
				.build();

		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
				token, List.of(new SimpleGrantedAuthority(user.getRole().authority()))));
	}

	public void signOut() {
		SecurityContextHolder.clearContext();
	}

}
