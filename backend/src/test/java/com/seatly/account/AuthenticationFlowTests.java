package com.seatly.account;

import com.seatly.account.view.AuthenticatedSession;
import com.seatly.support.IntegrationTest;
import com.seatly.support.SeatlyFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Registering, signing in, and keeping a session alive.
 */
@Transactional
class AuthenticationFlowTests extends IntegrationTest {

	private static final String EMAIL = "aman@example.com";
	private static final String PASSWORD = "correct-horse-battery";

	@Autowired
	private AuthenticationService authentication;

	@Autowired
	private RefreshTokenRepository refreshTokens;

	@Autowired
	private RefreshTokens refreshTokenValues;

	@Autowired
	private JwtDecoder jwtDecoder;

	@Autowired
	private SeatlyFixtures fixtures;

	@Autowired
	private EntityManager entityManager;

	@BeforeEach
	void clean() {
		fixtures.wipe();
	}

	@Test
	void registeringIssuesAWorkingSession() {
		AuthenticatedSession session = authentication.register(EMAIL, PASSWORD, "Aman");

		assertThat(session.user().email()).isEqualTo(EMAIL);
		assertThat(session.user().role()).isEqualTo(Role.CUSTOMER);
		assertThat(session.refreshToken()).isNotBlank();

		Jwt token = jwtDecoder.decode(session.accessToken());
		assertThat(token.getSubject()).isEqualTo(String.valueOf(session.user().id()));
		assertThat(token.getClaimAsString("role")).isEqualTo("CUSTOMER");
	}

	/** Nothing that could identify the customer travels in the token. */
	@Test
	void theAccessTokenCarriesOnlyWhatIsNeededToAuthorise() {
		AuthenticatedSession session = authentication.register(EMAIL, PASSWORD, "Aman");

		Jwt token = jwtDecoder.decode(session.accessToken());

		assertThat(token.getClaims()).containsOnlyKeys("iss", "sub", "iat", "exp", "role");
	}

	@Test
	void theRefreshTokenIsNeverStoredInTheClear() {
		AuthenticatedSession session = authentication.register(EMAIL, PASSWORD, "Aman");

		assertThat(refreshTokens.findByTokenHash(session.refreshToken())).isEmpty();
		assertThat(refreshTokens.findByTokenHash(refreshTokenValues.hash(session.refreshToken())))
				.isPresent();
	}

	@Test
	void anAddressCanOnlyBeRegisteredOnce() {
		authentication.register(EMAIL, PASSWORD, "Aman");

		assertThatThrownBy(() -> authentication.register("AMAN@example.com", PASSWORD, "Someone else"))
				.isInstanceOf(EmailAlreadyRegisteredException.class);
	}

	@Test
	void signsInWithTheRightPassword() {
		authentication.register(EMAIL, PASSWORD, "Aman");

		assertThat(authentication.signIn(EMAIL, PASSWORD).accessToken()).isNotBlank();
	}

	@Test
	void refusesTheWrongPassword() {
		authentication.register(EMAIL, PASSWORD, "Aman");

		assertThatThrownBy(() -> authentication.signIn(EMAIL, "not-the-password"))
				.isInstanceOf(InvalidCredentialsException.class);
	}

	/** Same exception, same message: nothing here reveals which addresses exist. */
	@Test
	void refusesAnUnknownAddressTheSameWay() {
		assertThatThrownBy(() -> authentication.signIn("nobody@example.com", PASSWORD))
				.isInstanceOf(InvalidCredentialsException.class)
				.hasMessage("Email or password is incorrect");
	}

	@Test
	void refreshingIssuesANewPairAndRetiresTheOldOne() {
		AuthenticatedSession first = authentication.register(EMAIL, PASSWORD, "Aman");

		AuthenticatedSession second = authentication.refresh(first.refreshToken());

		assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
		assertThat(refreshTokens.findByTokenHash(refreshTokenValues.hash(first.refreshToken()))
				.orElseThrow().isRevoked()).isTrue();
	}

	/** Rotation keeps the session, so the family carries on across refreshes. */
	@Test
	void aRefreshedTokenStaysInTheSameFamily() {
		AuthenticatedSession first = authentication.register(EMAIL, PASSWORD, "Aman");
		AuthenticatedSession second = authentication.refresh(first.refreshToken());

		RefreshToken before = refreshTokens
				.findByTokenHash(refreshTokenValues.hash(first.refreshToken())).orElseThrow();
		RefreshToken after = refreshTokens
				.findByTokenHash(refreshTokenValues.hash(second.refreshToken())).orElseThrow();

		assertThat(after.getFamilyId()).isEqualTo(before.getFamilyId());
	}

	/**
	 * The theft case.
	 * <p>
	 * A spent token turning up again means two parties hold it. There is no way
	 * to tell which is the customer, so the session ends for both.
	 */
	@Test
	void reusingASpentTokenKillsTheWholeFamily() {
		AuthenticatedSession first = authentication.register(EMAIL, PASSWORD, "Aman");
		AuthenticatedSession second = authentication.refresh(first.refreshToken());

		// The thief replays the token the real browser has already spent.
		assertThatThrownBy(() -> authentication.refresh(first.refreshToken()))
				.isInstanceOf(InvalidRefreshTokenException.class);

		// And the token the real browser is holding is dead too.
		assertThatThrownBy(() -> authentication.refresh(second.refreshToken()))
				.isInstanceOf(InvalidRefreshTokenException.class);
	}

	@Test
	void refusesARefreshTokenItHasNeverSeen() {
		assertThatThrownBy(() -> authentication.refresh("not-a-real-token"))
				.isInstanceOf(InvalidRefreshTokenException.class);
	}

	@Test
	void signingOutEndsTheSession() {
		AuthenticatedSession session = authentication.register(EMAIL, PASSWORD, "Aman");

		authentication.signOut(session.refreshToken());

		assertThatThrownBy(() -> authentication.refresh(session.refreshToken()))
				.isInstanceOf(InvalidRefreshTokenException.class);
	}

	@Test
	void aDisabledAccountCannotRefresh() {
		AuthenticatedSession session = authentication.register(EMAIL, PASSWORD, "Aman");
		fixtures.disableAccount(session.user().id());
		// The update went straight to the database, so the copy Hibernate is
		// already holding still says enabled. Clearing makes the next read go back
		// to the row rather than to the first-level cache.
		entityManager.flush();
		entityManager.clear();

		assertThatThrownBy(() -> authentication.refresh(session.refreshToken()))
				.isInstanceOf(InvalidRefreshTokenException.class);
	}

}
