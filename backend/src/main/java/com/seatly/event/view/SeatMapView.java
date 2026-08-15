package com.seatly.event.view;

import com.seatly.event.EventSeatStatus;

import java.time.Instant;
import java.util.List;

/**
 * The seat map as the browser needs it: grouped into sections and rows, ready to
 * render as a grid without the client having to reassemble anything.
 * <p>
 * Records rather than entities. Serialising entities straight out of a
 * controller drags lazy associations into the JSON encoder, couples the wire
 * format to the schema, and leaks columns nobody asked for.
 */
public record SeatMapView(
		Long eventId,
		String title,
		String venueName,
		String city,
		Instant startsAt,
		String currency,
		List<SectionView> sections) {

	public record SectionView(String name, int displayOrder, List<RowView> rows) {
	}

	public record RowView(String label, List<SeatView> seats) {
	}

	/**
	 * {@code status} is the effective status at the time of the request, not the
	 * raw column: a lapsed hold is reported as AVAILABLE.
	 */
	public record SeatView(
			Long eventSeatId,
			int number,
			String label,
			EventSeatStatus status,
			long priceMinor) {
	}

}
