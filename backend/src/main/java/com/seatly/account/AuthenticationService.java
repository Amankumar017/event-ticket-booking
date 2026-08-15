package com.seatly.account;

import com.seatly.account.view.AuthenticatedSession;
import com.seatly.account.view.CurrentUser;
import com.seatly.common.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Registration, sign-in, and keeping a session alive.
 */
@Service
public class AuthenticationService {

	private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

	private final AppUserRepository users;
	private final RefreshTokenRepository refreshTokens;
	private final PasswordEncoder passwords;
	private final AccessTokens accessTokens;
	private final RefreshTokens refreshTokenValues;
	private final SecurityProperties properties;
	private final Clock clock;

	public AuthenticationService(AppUserRepository users, RefreshTokenRepository refreshTokens,
			PasswordEncoder passwords, AccessTokens accessTokens, RefreshTokens refreshTokenValues,
			SecurityProperties properties, Clock clock) {
		this.users = users;
		this.refreshTokens = refreshTokens;
		this.passwords = passwords;
		this.accessTokens = accessTokens;
		this.refreshTokenValues = refreshTokenValues;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public AuthenticatedSession register(String email, String rawPassword, String displayName) {
		if (users.existsByEmail(email)) {
			throw new EmailAlreadyRegisteredException();
		}

		AppUser user = users.save(new AppUser(
				email, passwords.encode(rawPassword), displayName, Role.CUSTOMER));

		return startSession(user, UUID.randomUUID());
	}

	/**
	 * Signs in, or refuses without saying which half was wrong.
	 * <p>
	 * "No account with that address" and "wrong password" are the same answer
	 * here on purpose: telling them apart hands an attacker a way to find out
	 * which addresses are registered.
	 */
	@Transactional
	public AuthenticatedSession signIn(String email, String rawPassword) {
		AppUser user = users.findByEmail(email).orElse(null);

		// The hash is verified even when no account was found, against a dummy
		// value. Skipping it would make a missing account answer measurably
		// faster than a wrong password, which is the same disclosure by another
		// route.
		String storedHash = user != null ? user.getPasswordHash() : NO_SUCH_USER_HASH;
		boolean passwordMatches = passwords.matches(rawPassword, storedHash);

		if (user == null || !passwordMatches || !user.isEnabled()) {
			throw new InvalidCredentialsException();
		}

		return startSession(user, UUID.randomUUID());
	}

	/**
	 * Exchanges a refresh token for a new pair, and retires the old one.
	 *
	 * <h2>Rotation</h2>
	 *
	 * Each refresh token may be spent exactly once. Using it revokes it and
	 * issues a successor in the same family, so a token captured from a network
	 * log or a stale backup is worthless the moment the real browser refreshes.
	 *
	 * <h2>Reuse means somebody has a copy</h2>
	 *
	 * A token that has already been spent turning up again means two parties hold
	 * the same value. There is no way to tell which one is the customer, so the
	 * entire family is revoked and both are made to sign in again. Losing a
	 * session is a small price; the alternative is leaving the thief with a
	 * renewable one.
	 *
	 * <h2>The revocation has to outlive the failure</h2>
	 *
	 * {@code noRollbackFor} is load-bearing. Every path that revokes anything here
	 * then throws, and a rollback would undo the revocation along with it; the
	 * response would say no while the database quietly kept the stolen session
	 * alive. Nothing else in this method writes before it throws, so committing
	 * on failure commits exactly the revocations and nothing more.
	 */
	@Transactional(noRollbackFor = InvalidRefreshTokenException.class)
	public AuthenticatedSession refresh(String presentedToken) {
		Instant now = clock.instant();
		RefreshToken stored = refreshTokens.findByTokenHash(refreshTokenValues.hash(presentedToken))
				.orElseThrow(InvalidRefreshTokenException::new);

		if (stored.isRevoked()) {
			int killed = refreshTokens.revokeFamily(stored.getFamilyId(), now);
			log.warn("Refresh token reuse detected for user {}; revoked {} live token(s) in family {}",
					stored.getUser().getId(), killed, stored.getFamilyId());
			throw new InvalidRefreshTokenException();
		}
		if (!stored.isUsableAt(now)) {
			throw new InvalidRefreshTokenException();
		}

		AppUser user = stored.getUser();
		if (!user.isEnabled()) {
			refreshTokens.revokeAllForUser(user.getId(), now);
			throw new InvalidRefreshTokenException();
		}

		stored.revokeAt(now);
		return startSession(user, stored.getFamilyId());
	}

	/** Ends this session. Other devices keep theirs. */
	@Transactional
	public void signOut(String presentedToken) {
		if (presentedToken == null) {
			return;
		}
		refreshTokens.findByTokenHash(refreshTokenValues.hash(presentedToken))
				.ifPresent(token -> refreshTokens.revokeFamily(token.getFamilyId(), clock.instant()));
	}

	@Transactional(readOnly = true)
	public CurrentUser describe(Long userId) {
		AppUser user = users.findById(userId)
				.orElseThrow(() -> NotFoundException.of("Account", userId));
		return new CurrentUser(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole());
	}

	private AuthenticatedSession startSession(AppUser user, UUID familyId) {
		Instant now = clock.instant();
		String refreshValue = refreshTokenValues.generate();

		refreshTokens.save(new RefreshToken(
				user,
				refreshTokenValues.hash(refreshValue),
				familyId,
				now.plus(properties.refreshTokenTtl())));

		return new AuthenticatedSession(
				accessTokens.issueFor(user),
				accessTokens.expiresInSeconds(),
				refreshValue,
				new CurrentUser(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole()));
	}

	/**
	 * A real BCrypt hash of a value nothing will ever match, used to keep the
	 * timing of a failed sign-in the same whether or not the account exists.
	 */
	private static final String NO_SUCH_USER_HASH =
			"$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

}
