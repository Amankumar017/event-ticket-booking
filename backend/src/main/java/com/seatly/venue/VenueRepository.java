package com.seatly.venue;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VenueRepository extends JpaRepository<Venue, Long> {

	Optional<Venue> findByName(String name);

	boolean existsByName(String name);

}
