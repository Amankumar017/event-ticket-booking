package com.seatly.common.outbox;

import com.seatly.support.IntegrationTest;
import com.seatly.support.SeatlyFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Transactional
class OutboxPublisherTests extends IntegrationTest {

	@Autowired
	private OutboxPublisher publisher;

	@Autowired
	private OutboxMessageRepository messages;

	@Autowired
	private SeatlyFixtures fixtures;

	@MockitoBean
	private MessageSender sender;

	@BeforeEach
	void clean() {
		fixtures.wipe();
	}

	@Test
	void sendsWhatIsWaitingAndMarksIt() {
		messages.save(new OutboxMessage("booking.confirmed", "aman@example.com", "your tickets"));

		assertThat(publisher.publishPending()).isEqualTo(1);

		verify(sender).send(any());
		assertThat(messages.findAll()).singleElement().satisfies(message -> {
			assertThat(message.isSent()).isTrue();
			assertThat(message.getAttempts()).isEqualTo(1);
		});
	}

	/** A message already sent is never picked up again. */
	@Test
	void doesNotSendTheSameMessageTwice() {
		messages.save(new OutboxMessage("booking.confirmed", "aman@example.com", "your tickets"));

		publisher.publishPending();
		assertThat(publisher.publishPending()).isZero();

		verify(sender, times(1)).send(any());
	}

	/** One message the sender cannot accept must not hold up the rest. */
	@Test
	void aFailureIsRecordedAndTheBatchCarriesOn() {
		willThrow(new IllegalStateException("mailbox full")).given(sender).send(any());
		messages.save(new OutboxMessage("booking.confirmed", "aman@example.com", "your tickets"));

		assertThat(publisher.publishPending()).isZero();

		assertThat(messages.findAll()).singleElement().satisfies(message -> {
			assertThat(message.isSent()).isFalse();
			assertThat(message.getAttempts()).isEqualTo(1);
		});
	}

	/** And it stops being retried once it has clearly failed for good. */
	@Test
	void aMessageIsAbandonedAfterEnoughAttempts() {
		willThrow(new IllegalStateException("mailbox full")).given(sender).send(any());
		messages.save(new OutboxMessage("booking.confirmed", "aman@example.com", "your tickets"));

		for (int attempt = 0; attempt < 6; attempt++) {
			publisher.publishPending();
		}

		verify(sender, times(5)).send(any());
		assertThat(messages.countBySentAtIsNull()).isEqualTo(1);
	}

}
