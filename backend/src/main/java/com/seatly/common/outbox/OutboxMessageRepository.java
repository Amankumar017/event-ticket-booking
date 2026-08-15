package com.seatly.common.outbox;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

	/**
	 * Messages still waiting to go out, oldest first.
	 * <p>
	 * Attempts are capped so that one message the sender can never accept does
	 * not consume every sweep forever while newer messages queue behind it.
	 */
	@Query("""
			select m from OutboxMessage m
			where m.sentAt is null and m.attempts < :maxAttempts
			order by m.id asc
			""")
	List<OutboxMessage> findUnsent(int maxAttempts, Limit limit);

	long countBySentAtIsNull();

}
