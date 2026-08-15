package com.seatly.common.metrics;

import com.seatly.common.outbox.OutboxMessageRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two queues worth an alert.
 *
 * <h2>Gauges, not counters</h2>
 *
 * These answer "how much is waiting right now", which is the shape of question
 * that matters for a backlog. A counter would tell you how many messages had
 * ever been written, which stays reassuring while nothing is being sent.
 * <p>
 * An outbox depth that climbs means confirmations are not reaching customers
 * even though their bookings are perfectly fine -- exactly the failure that is
 * invisible from the outside.
 */
@Configuration
public class SeatlyGauges {

	@Bean
	public Gauge outboxDepth(MeterRegistry registry, OutboxMessageRepository outbox) {
		return Gauge.builder("seatly.outbox.pending", outbox, OutboxMessageRepository::countBySentAtIsNull)
				.description("Messages written but not yet sent")
				.register(registry);
	}

}
