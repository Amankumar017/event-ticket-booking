package com.seatly.account;

import com.seatly.account.view.AuthenticatedSession;
import com.seatly.account.view.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	static final String REFRESH_COOKIE = "seatly_refresh";

	private final AuthenticationService authentication;
	private final SecurityProperties properties;
	private final CurrentAccount currentAccount;

	public AuthController(AuthenticationService authentication, SecurityProperties properties,
			CurrentAccount currentAccount) {
		this.authentication = authentication;
		this.properties = properties;
		this.currentAccount = currentAccount;
	}

	public record RegistrationRequest(
			@NotBlank @Email @Size(max = 160) String email,
			@NotBlank @Size(min = 12, max = 128,
					message = "password must be at least 12 characters") String password,
			@NotBlank @Size(max = 120) String displayName) {
	}

	public record SignInRequest(
			@NotBlank @Email String email,
			@NotBlank String password) {
	}

	/** What the caller gets back. The refresh token is not in here. */
	public record SessionResponse(String accessToken, long expiresInSeconds, CurrentUser user) {
	}

	@PostMapping("/register")
	public ResponseEntity<SessionResponse> register(@Valid @RequestBody RegistrationRequest request) {
		AuthenticatedSession session = authentication.register(
				request.email(), request.password(), request.displayName());
		return respondWith(session, HttpStatus.CREATED);
	}

	@PostMapping("/login")
	public ResponseEntity<SessionResponse> login(@Valid @RequestBody SignInRequest request) {
		return respondWith(authentication.signIn(request.email(), request.password()), HttpStatus.OK);
	}

	/**
	 * Exchanges the refresh cookie for a new access token, and a new cookie.
	 * <p>
	 * The token is read from the cookie rather than the body, so a client cannot
	 * be talked into sending it anywhere else and script on the page cannot read
	 * it at all.
	 */
	@PostMapping("/refresh")
	public ResponseEntity<SessionResponse> refresh(
			@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
		if (refreshToken == null) {
			throw new InvalidRefreshTokenException();
		}
		return respondWith(authentication.refresh(refreshToken), HttpStatus.OK);
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(
			@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
		authentication.signOut(refreshToken);
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, expiredCookie().toString())
				.build();
	}

	@GetMapping("/me")
	public CurrentUser me() {
		return authentication.describe(currentAccount.id());
	}

	private ResponseEntity<SessionResponse> respondWith(AuthenticatedSession session, HttpStatus status) {
		return ResponseEntity.status(status)
				.header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
				.body(new SessionResponse(
						session.accessToken(), session.expiresInSeconds(), session.user()));
	}

	/**
	 * The refresh token's only home.
	 * <p>
	 * {@code httpOnly} keeps it away from any script on the page, so an XSS bug
	 * cannot walk off with a renewable session. {@code SameSite=Strict} means the
	 * browser will not attach it to a request another site started, which is what
	 * makes it safe to run without CSRF tokens. The path narrows it further: it is
	 * sent only to the endpoints that consume it, and never rides along with an
	 * ordinary API call.
	 */
	private ResponseCookie refreshCookie(String value) {
		return ResponseCookie.from(REFRESH_COOKIE, value)
				.httpOnly(true)
				.secure(properties.cookieSecure())
				.sameSite("Strict")
				.path("/api/auth")
				.maxAge(properties.refreshTokenTtl())
				.build();
	}

	private ResponseCookie expiredCookie() {
		return ResponseCookie.from(REFRESH_COOKIE, "")
				.httpOnly(true)
				.secure(properties.cookieSecure())
				.sameSite("Strict")
				.path("/api/auth")
				.maxAge(Duration.ZERO)
				.build();
	}

}
