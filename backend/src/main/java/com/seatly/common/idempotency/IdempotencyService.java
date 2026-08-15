package com.seatly.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Runs an operation at most once per idempotency key.
 *
 * <h2>The problem</h2>
 *
 * A client whose request times out has no way to know whether the work happened.
 * Retrying risks doing it twice; not retrying risks not doing it at all. An
 * idempotency key lets the client retry safely: the same key means "this is the
 * request I already sent", and the server answers with what it answered before.
 *
 * <h2>Claim first, work second</h2>
 *
 * The key row is inserted before the work starts, in its own transaction, so the
 * claim is visible to everybody immediately. Two identical requests arriving
 * together therefore race on a unique index rather than on the work itself --
 * one wins and proceeds, the other finds the row and is told the request is
 * already in flight.
 * <p>
 * Doing it the other way round, work, then record the key, leaves a window
 * where both callers are mid-payment and neither has recorded anything.
 */
@Service
public class IdempotencyService {

	private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

	private final IdempotencyRecordRepository records;
	private final ObjectMapper json;
	private final IdempotencyService self;

	public IdempotencyService(IdempotencyRecordRepository records, ObjectMapper json,
			@org.springframework.context.annotation.Lazy IdempotencyService self) {
		this.records = records;
		this.json = json;
		this.self = self;
	}

	/**
	 * Executes {@code work} unless this key has already been used.
	 *
	 * @param key         the client's key; when null the work simply runs
	 * @param userId      whose key it is, keys are scoped per account
	 * @param requestBody the request, hashed so a key reused with different
	 *                    content can be refused rather than answered wrongly
	 * @param resultType  the type stored responses are read back as
	 */
	public <T> T runOnce(String key, Long userId, Object requestBody, Class<T> resultType,
			Supplier<T> work) {
		if (key == null || key.isBlank()) {
			return work.get();
		}

		String fingerprint = fingerprintOf(requestBody);

		Optional<IdempotencyRecord> claimed;
		try {
			claimed = self.claim(key, userId, fingerprint);
		}
		catch (DataIntegrityViolationException raced) {
			// Two identical requests arrived together and this one lost at the
			// unique index. The exception is caught out here, outside the claim's
			// transaction boundary, so that the failed insert rolls that inner
			// transaction back cleanly instead of leaving it marked rollback-only
			// underneath a caller that thinks it recovered.
			claimed = Optional.empty();
		}

		if (claimed.isEmpty()) {
			// Somebody else holds the key. Either they finished, and their answer
			// is the answer, or they are still going and this caller must wait.
			return replayOrRefuse(key, userId, fingerprint, resultType);
		}

		T result;
		try {
			result = work.get();
		}
		catch (RuntimeException failure) {
			// The claim is released so the client can genuinely retry. Keeping it
			// would answer every retry with a failure that might have been
			// transient.
			self.release(key, userId);
			throw failure;
		}

		self.complete(key, userId, result);
		return result;
	}

	/**
	 * Takes the key, or reports that somebody else already has it.
	 * <p>
	 * REQUIRES_NEW so the claim commits on its own. Inside the caller's
	 * transaction it would be invisible to a competing request until the work
	 * finished, which is exactly the window this is meant to close.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<IdempotencyRecord> claim(String key, Long userId, String fingerprint) {
		if (records.findByUserIdAndKey(userId, key).isPresent()) {
			return Optional.empty();
		}
		// A concurrent claim that slips past that check fails at the unique index
		// and is handled by the caller. Deliberately not caught here: a constraint
		// violation dooms the transaction it happened in, and swallowing it would
		// only move the failure to the commit.
		return Optional.of(records.saveAndFlush(new IdempotencyRecord(key, userId, fingerprint)));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void complete(String key, Long userId, Object result) {
		records.findByUserIdAndKey(userId, key).ifPresent(record -> {
			try {
				record.complete(200, json.writeValueAsString(result));
			}
			catch (Exception cannotSerialise) {
				log.warn("Could not store the response for idempotency key {}", key, cannotSerialise);
			}
		});
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void release(String key, Long userId) {
		records.findByUserIdAndKey(userId, key).ifPresent(records::delete);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public <T> T replayOrRefuse(String key, Long userId, String fingerprint, Class<T> resultType) {
		IdempotencyRecord existing = records.findByUserIdAndKey(userId, key)
				.orElseThrow(() -> new IdempotencyConflictException(
						"That request is already being processed. Try again in a moment."));

		if (!existing.matches(fingerprint)) {
			throw new IdempotencyKeyReusedException();
		}
		if (!existing.isCompleted()) {
			throw new IdempotencyConflictException(
					"That request is already being processed. Try again in a moment.");
		}

		try {
			return json.readValue(existing.getResponseBody(), resultType);
		}
		catch (Exception cannotRead) {
			throw new IllegalStateException("Stored idempotent response could not be read", cannotRead);
		}
	}

	/**
	 * A hash of the request, so the stored reply is only ever handed back for the
	 * request that produced it.
	 */
	private String fingerprintOf(Object requestBody) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(json.writeValueAsBytes(requestBody));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException | JsonProcessingException cannotFingerprint) {
			throw new IllegalStateException("Could not fingerprint the request", cannotFingerprint);
		}
	}

}
