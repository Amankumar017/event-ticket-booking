import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MinorCurrencyPipe } from '../../core/minor-currency-pipe';
import { SeatMap, SeatView } from '../../core/seatly-models';

/**
 * The seating chart for one event.
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
  readonly eventId = input.required<string>();

  protected readonly map = httpResource<SeatMap>(
    () => `/api/events/${this.eventId()}/seats`,
  );

  /** Ids of the seats this customer has picked, not yet sent anywhere. */
  private readonly selectedIds = signal<ReadonlySet<number>>(new Set());

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
    if (seat.status !== 'AVAILABLE') {
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

  protected seatTitle(seat: SeatView): string {
    const price = new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency: 'INR',
      maximumFractionDigits: 0,
    }).format(seat.priceMinor / 100);
    return `${seat.label} — ${price} — ${seat.status.toLowerCase()}`;
  }
}
