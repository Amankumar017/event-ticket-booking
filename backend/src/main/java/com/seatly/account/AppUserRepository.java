package com.seatly.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	/**
	 * Matched case-insensitively, the same way the unique index is built. Doing
	 * one without the other is how two accounts end up sharing an address.
	 */
	@Query("select u from AppUser u where lower(u.email) = lower(:email)")
	Optional<AppUser> findByEmail(String email);

	@Query("select count(u) > 0 from AppUser u where lower(u.email) = lower(:email)")
	boolean existsByEmail(String email);

}
