package com.seatly.account;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param jwtSecret        signing key for access tokens, at least 32 bytes
 * @param accessTokenTtl   how long an access token is accepted for
 * @param refreshTokenTtl  how long a session can be kept alive by refreshing
 * @param cookieSecure     whether the refresh cookie requires HTTPS
 */
@ConfigurationProperties(prefix = "seatly.security")
public record SecurityProperties(
		String jwtSecret,
		Duration accessTokenTtl,
		Duration refreshTokenTtl,
		boolean cookieSecure) {

	public SecurityProperties {
		// HMAC-SHA256 needs a key at least as long as its output. A shorter one is
		// rejected by Nimbus at startup anyway; failing here says why.
		if (jwtSecret == null || jwtSecret.getBytes().length < 32) {
			throw new IllegalArgumentException(
					"seatly.security.jwt-secret must be at least 32 bytes");
		}
		if (accessTokenTtl == null || accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
			throw new IllegalArgumentException("seatly.security.access-token-ttl must be positive");
		}
		if (refreshTokenTtl == null || refreshTokenTtl.compareTo(accessTokenTtl) <= 0) {
			throw new IllegalArgumentException(
					"seatly.security.refresh-token-ttl must outlast the access token");
		}
	}

}
