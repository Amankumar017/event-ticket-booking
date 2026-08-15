package com.seatly.event;

/**
 * Projection for the "seats still buyable, per event" aggregate.
 */
public record AvailableSeatCount(Long eventId, long available) {
}
