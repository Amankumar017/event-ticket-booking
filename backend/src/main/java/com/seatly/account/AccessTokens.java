package com.seatly.account;

import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Mints the short-lived token that authorises each request.
 *
 * <h2>What goes in it</h2>
 *
 * The subject is the account id, and the only other claim is the role. An access
 * token is read by anything holding it, so it carries what is needed to
 * authorise a call and nothing else -- no email, no name, nothing that would
 * turn a leaked token into a leak of personal data.
 *
 * <h2>Why it is short-lived</h2>
 *
 * Nothing can revoke a signed token before it expires; that is the trade made
 * for not hitting the database on every request. Fifteen minutes bounds the
 * damage. Anything that must take effect immediately -- a ban, a sign-out
 * everywhere -- is enforced on the refresh path, which does read the database.
 */
@Component
public class AccessTokens {

	static final String ROLE_CLAIM = "role";

	private final JwtEncoder encoder;
	private final SecurityProperties properties;
	private final Clock clock;

	public AccessTokens(JwtEncoder encoder, SecurityProperties properties, Clock clock) {
		this.encoder = encoder;
		this.properties = properties;
		this.clock = clock;
	}

	public String issueFor(AppUser user) {
		Instant now = clock.instant();

		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer("seatly")
				.subject(String.valueOf(user.getId()))
				.issuedAt(now)
				.expiresAt(now.plus(properties.accessTokenTtl()))
				.claim(ROLE_CLAIM, user.getRole().name())
				.build();

		return encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}

	public long expiresInSeconds() {
		return properties.accessTokenTtl().toSeconds();
	}

}
