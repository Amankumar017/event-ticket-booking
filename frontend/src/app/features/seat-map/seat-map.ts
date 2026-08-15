import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { Auth } from '../../core/auth';
import { BookingApi } from '../../core/booking-api';
import { Countdown } from '../../core/countdown';
import { MinorCurrencyPipe } from '../../core/minor-currency-pipe';
import { SeatStream } from '../../core/seat-stream';
import { Booking, SeatMap, SectionView, SeatView } from '../../core/seatly-models';
import { switchMap } from 'rxjs';

/**
 * The seating chart for one event, and the hold placed on it.
 *
 * {@code eventId} arrives as a route parameter bound straight to an input
 * signal, which means the resource below re-fetches by itself when the route
 * changes, no subscription to `paramMap`, no manual reload.
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
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);
  private readonly countdown = inject(Countdown);
  private readonly stream = inject(SeatStream);

  readonly eventId = input.required<string>();

  protected readonly map = httpResource<SeatMap>(
    () => `/api/events/${this.eventId()}/seats`,
  );

  private readonly liveStatuses = this.stream.latest();

  /**
   * The chart as fetched, with anything the stream has since reported laid over
   * the top.
   *
   * Computed rather than written back into the resource: the fetched chart stays
   * the fetched chart, so a reload replaces it cleanly and there is no merged
   * state to get out of step.
   */
  protected readonly liveMap = computed<SeatMap | undefined>(() => {
    const fetched = this.map.value();
    const live = this.liveStatuses();
    if (!fetched || live.size === 0) {
      return fetched;
    }

    return {
      ...fetched,
      sections: fetched.sections.map((section) => ({
        ...section,
        rows: section.rows.map((row) => ({
          ...row,
          seats: row.seats.map((seat) => {
            const change = live.get(seat.eventSeatId);
            return change ? { ...seat, status: change.status } : seat;
          }),
        })),
      })),
    };
  });

  constructor() {
    // The route parameter is a signal, so this follows the customer from one
    // event to another without anybody having to remember to resubscribe.
    effect(() => this.stream.watch(this.eventId()));
    inject(DestroyRef).onDestroy(() => this.stream.stop());
  }

  /** Ids of the seats this customer has picked, not yet sent anywhere. */
  private readonly selectedIds = signal<ReadonlySet<number>>(new Set());

  /** The hold, once the server has granted one. */
  protected readonly booking = signal<Booking | null>(null);
  protected readonly working = signal(false);

  /** Minted per payment attempt, kept so a retry reuses it rather than paying twice. */
  private idempotencyKey: string | null = null;
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
    for (const section of this.liveMap()?.sections ?? []) {
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

  /** What the customer has picked, named the way the tickets will be. */
  protected readonly selectedLabels = computed(() => {
    const chosen = this.selectedIds();
    const labels: string[] = [];
    for (const section of this.liveMap()?.sections ?? []) {
      for (const row of section.rows) {
        for (const seat of row.seats) {
          if (chosen.has(seat.eventSeatId)) {
            labels.push(seat.label);
          }
        }
      }
    }
    return labels.join(', ');
  });

  protected seatLabels(booking: Booking): string {
    return booking.seats.map((seat) => seat.label).join(', ');
  }

  /** What a seat in this section costs. Every seat in one shares a price. */
  protected priceOf(section: SectionView): number {
    return Math.min(...section.rows.flatMap((row) => row.seats.map((seat) => seat.priceMinor)));
  }

  /**
   * Whether this seat's status arrived over the live stream rather than with the
   * page. Drives a one-off flare, so somebody else taking a seat is something
   * you notice instead of something you find out about later.
   */
  protected hasJustChanged(seat: SeatView): boolean {
    return this.liveStatuses().has(seat.eventSeatId);
  }

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

    // Sending an anonymous visitor to sign in first is a courtesy, not a
    // control: the server refuses an unauthenticated hold regardless.
    if (!this.auth.isSignedIn()) {
      this.router.navigate(['/sign-in'], {
        queryParams: { returnTo: `/events/${this.eventId()}` },
      });
      return;
    }

    this.begin();
    this.bookingApi
      .hold({
        eventId: seatMap.eventId,
        eventSeatIds: [...this.selectedIds()],
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

  /**
   * Pays for the hold.
   *
   * Three steps, because that is what paying actually involves: open a payment,
   * settle it at the provider, then read back the booking the provider's webhook
   * has confirmed. The middle step is the only one a real integration would
   * replace, and it would be replaced by sending the customer to the provider.
   *
   * The idempotency key is minted once per attempt and reused on retry, so a
   * request that times out cannot become two payments.
   */
  protected pay(): void {
    const reference = this.booking()?.reference;
    if (!reference) {
      return;
    }

    this.idempotencyKey ??= crypto.randomUUID();

    this.begin();
    this.bookingApi
      .startPayment(reference, this.idempotencyKey)
      .pipe(
        switchMap((intent) => this.bookingApi.settlePayment(intent.paymentReference, 'succeeded')),
        switchMap(() => this.bookingApi.byReference(reference)),
      )
      .subscribe({
        next: (paid) => {
          this.booking.set(paid);
          this.idempotencyKey = null;
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
    this.idempotencyKey = null;
    this.problem.set(null);
    this.map.reload();
  }

  protected seatTitle(seat: SeatView): string {
    const price = new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency: 'INR',
      maximumFractionDigits: 0,
    }).format(seat.priceMinor / 100);
    return `${seat.label} · ${price} · ${seat.status.toLowerCase()}`;
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
   * message shown is the one it wrote, "Seat A1 is no longer available" rather
   * than a status code the customer has to interpret.
   */
  private failed(failure: unknown): void {
    const problem = (failure as { error?: { detail?: string } })?.error;
    this.problem.set(problem?.detail ?? 'Something went wrong. Please try again.');
    this.working.set(false);
    this.map.reload();
  }
}
