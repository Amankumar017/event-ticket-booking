package com.seatly.common;

/**
 * Thrown when a request names something that does not exist.
 * <p>
 * Deliberately free of any HTTP vocabulary: the service layer says what
 * happened, and the web layer decides that it means 404. Keeping the two apart
 * is what lets the same service be called from a scheduled job or a test without
 * dragging servlet concepts along.
 */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String message) {
		super(message);
	}

	public static NotFoundException of(String what, Object id) {
		return new NotFoundException(what + " " + id + " was not found");
	}

}
