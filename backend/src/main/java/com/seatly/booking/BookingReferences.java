package com.seatly.booking;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates the short public identifier printed on a ticket.
 * <p>
 * The alphabet leaves out I, O, 0 and 1, because these get read aloud down a
 * phone line and typed back in by somebody who cannot tell them apart. Eight
 * characters from a 32-symbol alphabet is 40 bits, which is far more than enough
 * to make collisions a non-event at this scale -- and the unique constraint on
 * the column is there to catch the case where it is not.
 */
@Component
public class BookingReferences {

	private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
	private static final int LENGTH = 8;

	private final SecureRandom random = new SecureRandom();

	public String next() {
		StringBuilder reference = new StringBuilder("SEAT-");
		for (int i = 0; i < LENGTH; i++) {
			reference.append(ALPHABET[random.nextInt(ALPHABET.length)]);
		}
		return reference.toString();
	}

}
