/**
 * Wire format of the Seatly API.
 *
 * These mirror the backend's view records exactly. Prices are in minor units
 * (paise) end to end -- the division by 100 happens once, in the pipe that
 * formats them, and never in arithmetic.
 */

export type SeatStatus = 'AVAILABLE' | 'HELD' | 'SOLD' | 'BLOCKED';

export interface EventSummary {
  readonly id: number;
  readonly title: string;
  readonly venueName: string;
  readonly city: string;
  readonly startsAt: string;
  readonly salesCloseAt: string;
  readonly availableSeats: number;
}

export interface SeatView {
  readonly eventSeatId: number;
  readonly number: number;
  readonly label: string;
  readonly status: SeatStatus;
  readonly priceMinor: number;
}

export interface RowView {
  readonly label: string;
  readonly seats: readonly SeatView[];
}

export interface SectionView {
  readonly name: string;
  readonly displayOrder: number;
  readonly rows: readonly RowView[];
}

export type BookingStatus = 'PENDING' | 'CONFIRMED' | 'EXPIRED' | 'CANCELLED';

export interface BookedSeat {
  readonly eventSeatId: number;
  readonly label: string;
  readonly priceMinor: number;
}

export interface Booking {
  readonly reference: string;
  readonly eventId: number;
  readonly status: BookingStatus;
  readonly totalMinor: number;
  readonly currency: string;
  /** When the hold lapses. Null once the booking is no longer pending. */
  readonly expiresAt: string | null;
  readonly confirmedAt: string | null;
  readonly seats: readonly BookedSeat[];
}

export type PaymentStatus = 'REQUIRES_PAYMENT' | 'SUCCEEDED' | 'FAILED';

export interface PaymentIntent {
  readonly paymentReference: string;
  readonly bookingReference: string;
  readonly amountMinor: number;
  readonly currency: string;
  readonly status: PaymentStatus;
  readonly failureReason: string | null;
  readonly settledAt: string | null;
}

/** Identity comes from the access token, so it is not in here. */
export interface HoldRequest {
  readonly eventId: number;
  readonly eventSeatIds: readonly number[];
}

export interface SeatMap {
  readonly eventId: number;
  readonly title: string;
  readonly venueName: string;
  readonly city: string;
  readonly startsAt: string;
  readonly currency: string;
  readonly sections: readonly SectionView[];
}
