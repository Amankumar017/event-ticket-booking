import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { BookingApi } from '../../core/booking-api';
import { Countdown } from '../../core/countdown';
import { MinorCurrencyPipe } from '../../core/minor-currency-pipe';
import { Booking, SeatMap, SeatView } from '../../core/seatly-models';

/**
 * The seating chart for one event, and the hold placed on it.
 *
 * {@code eventId} arrives as a route parameter bound straight to an input
 * signal, which means the resource below re-fetches by itself when the route
 * changes -- no subscription to `paramMap`, no manual reload.
 */
@Component({
  selector: 'app-seat-map',
  imports: [RouterLink, DatePipe, MinorCurrencyPipe],
  templateUrl: './seat-map.html',
  styleUrl: './seat-map.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SeatMapPage {
  private readonly bookingApi = inject(BookingApi);
  private readonly countdown = inject(Countdown);

  readonly eventId = input.required<string>();

  protected readonly map = httpResource<SeatMap>(
    () => `/api/events/${this.eventId()}/seats`,
  );

  /** Ids of the seats this customer has picked, not yet sent anywhere. */
  private readonly selectedIds = signal<ReadonlySet<number>>(new Set());

  /** The hold, once the server has granted one. */
  protected readonly booking = signal<Booking | null>(null);
  protected readonly working = signal(false);
  protected readonly problem = signal<string | null>(null);

  private readonly deadline = computed(() => this.booking()?.expiresAt ?? null);
  protected readonly secondsLeft = this.countdown.secondsUntil(this.deadline);

  protected readonly timeLeft = computed(() => Countdown.format(this.secondsLeft()));

  /**
   * A pending hold whose clock has run out. The server would refuse to confirm
   * it, so the button goes away rather than lying about what it will do.
   */
  protected readonly holdHasLapsed = computed(
    () => this.booking()?.status === 'PENDING' && this.secondsLeft() === 0,
  );

  protected readonly selectedCount = computed(() => this.selectedIds().size);

  /** Summed in paise. Currency arithmetic never touches a floating point. */
  protected readonly selectedTotalMinor = computed(() => {
    const chosen = this.selectedIds();
    let total = 0;
    for (const section of this.map.value()?.sections ?? []) {
      for (const row of section.rows) {
        for (const seat of row.seats) {
          if (chosen.has(seat.eventSeatId)) {
            total += seat.priceMinor;
          }
        }
      }
    }
    return total;
  });

  protected isSelected(seat: SeatView): boolean {
    return this.selectedIds().has(seat.eventSeatId);
  }

  protected toggle(seat: SeatView): void {
    if (seat.status !== 'AVAILABLE' || this.booking()) {
      return;
    }
    // A new Set each time: mutating the old one in place would not change the
    // signal's identity, and nothing would re-render.
    const next = new Set(this.selectedIds());
    if (!next.delete(seat.eventSeatId)) {
      next.add(seat.eventSeatId);
    }
    this.selectedIds.set(next);
  }

  protected hold(): void {
    const seatMap = this.map.value();
    if (!seatMap || this.selectedCount() === 0) {
      return;
    }

    this.begin();
    this.bookingApi
      .hold({
        eventId: seatMap.eventId,
        eventSeatIds: [...this.selectedIds()],
        customerName: 'Aman',
        customerEmail: 'aman@example.com',
      })
      .subscribe({
        next: (held) => {
          this.booking.set(held);
          this.selectedIds.set(new Set());
          this.done();
        },
        error: (failure) => this.failed(failure),
      });
  }

  protected confirm(): void {
    const reference = this.booking()?.reference;
    if (!reference) {
      return;
    }

    this.begin();
    this.bookingApi.confirm(reference).subscribe({
      next: (confirmed) => {
        this.booking.set(confirmed);
        this.done();
      },
      error: (failure) => this.failed(failure),
    });
  }

  protected cancel(): void {
    const reference = this.booking()?.reference;
    if (!reference) {
      return;
    }

    this.begin();
    this.bookingApi.cancel(reference).subscribe({
      next: () => {
        this.booking.set(null);
        this.done();
      },
      error: (failure) => this.failed(failure),
    });
  }

  /** Drops the hold from view and reloads the chart as the server now sees it. */
  protected startAgain(): void {
    this.booking.set(null);
    this.problem.set(null);
    this.map.reload();
  }

  protected seatTitle(seat: SeatView): string {
    const price = new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency: 'INR',
      maximumFractionDigits: 0,
    }).format(seat.priceMinor / 100);
    return `${seat.label} — ${price} — ${seat.status.toLowerCase()}`;
  }

  private begin(): void {
    this.working.set(true);
    this.problem.set(null);
  }

  private done(): void {
    this.working.set(false);
    this.map.reload();
  }

  /**
   * The server answers failures with an RFC 9457 problem document, so the
   * message shown is the one it wrote -- "Seat A1 is no longer available" rather
   * than a status code the customer has to interpret.
   */
  private failed(failure: unknown): void {
    const problem = (failure as { error?: { detail?: string } })?.error;
    this.problem.set(problem?.detail ?? 'Something went wrong. Please try again.');
    this.working.set(false);
    this.map.reload();
  }
}
