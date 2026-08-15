package com.seatly.account;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates refresh tokens and hashes them for storage.
 *
 * <h2>Opaque, not a JWT</h2>
 *
 * A refresh token needs the one property a signed token cannot have: it must be
 * revocable the instant somebody signs out or a theft is spotted. So it is
 * simply 256 bits of randomness with no meaning of its own, and every question
 * about it is answered by the row it points at.
 *
 * <h2>Hashed with SHA-256, not BCrypt</h2>
 *
 * Deliberately different from passwords. BCrypt is slow on purpose because
 * passwords are short, guessable and reused; that reasoning does not apply to
 * 256 random bits, which no amount of guessing will reach. What matters here is
 * a lookup by hash on every refresh, and a plain digest keeps that a single
 * indexed read instead of a table scan comparing BCrypt hashes one at a time.
 */
@Component
public class RefreshTokens {

	private final SecureRandom random = new SecureRandom();

	/** A fresh token value. Handed to the browser once and never stored as-is. */
	public String generate() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** The stored form: a 64-character hex digest. */
	public String hash(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(token.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException impossible) {
			// Every JVM ships SHA-256; this cannot happen.
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

}
