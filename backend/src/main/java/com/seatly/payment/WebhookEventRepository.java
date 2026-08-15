package com.seatly.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

	Optional<WebhookEvent> findByProviderEventId(String providerEventId);

	boolean existsByProviderEventId(String providerEventId);

}
