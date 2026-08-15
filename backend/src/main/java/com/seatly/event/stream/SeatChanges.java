package com.seatly.event.stream;

import com.seatly.event.EventSeat;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * Announces seat changes to the rest of the application.
 *
 * <h2>Published inside the transaction, delivered after it</h2>
 *
 * Callers announce while they still hold the seat locks, which is the only
 * moment they know what they changed. Nothing is sent to a browser at that
 * point: the listener that does the sending waits for the commit. A change
 * announced by a transaction that then rolls back is simply never delivered,
 * so no customer is ever shown a seat that was taken and then untaken.
 */
@Component
public class SeatChanges {

	private final ApplicationEventPublisher publisher;

	public SeatChanges(ApplicationEventPublisher publisher) {
		this.publisher = publisher;
	}

	public void announce(Collection<EventSeat> seats) {
		seats.forEach(seat -> publisher.publishEvent(SeatChanged.of(seat)));
	}

}
