package com.seatly.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	/**
	 * Revokes every live token descended from one login.
	 * <p>
	 * Called when a token that was already used turns up again, which means two
	 * parties hold the same value and one of them should not. There is no way to
	 * tell the thief from the victim, so the session ends for both and whoever is
	 * genuine signs in again.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update RefreshToken t
			set t.revokedAt = :moment
			where t.familyId = :familyId and t.revokedAt is null
			""")
	int revokeFamily(UUID familyId, Instant moment);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update RefreshToken t
			set t.revokedAt = :moment
			where t.user.id = :userId and t.revokedAt is null
			""")
	int revokeAllForUser(Long userId, Instant moment);

	long countByFamilyIdAndRevokedAtIsNull(UUID familyId);

}
