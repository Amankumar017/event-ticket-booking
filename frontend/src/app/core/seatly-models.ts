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

export interface SeatMap {
  readonly eventId: number;
  readonly title: string;
  readonly venueName: string;
  readonly city: string;
  readonly startsAt: string;
  readonly currency: string;
  readonly sections: readonly SectionView[];
}
