import { DestroyRef, Injectable, NgZone, Signal, computed, inject, signal } from '@angular/core';
import { SeatStatus } from './seatly-models';

export interface SeatChanged {
  readonly eventId: number;
  readonly eventSeatId: number;
  readonly status: SeatStatus;
  readonly heldUntil: string | null;
}

/**
 * Live seat changes for one event.
 *
 * <h2>EventSource, not a WebSocket</h2>
 *
 * The traffic is one way, and the browser already knows how to reconnect an
 * `EventSource` on its own, including backing off, and telling the server
 * where it left off. A WebSocket would mean writing that reconnect loop by hand
 * for a direction nothing here uses.
 *
 * <h2>Latest state per seat, not a log</h2>
 *
 * What a seat map needs is what each seat is now. Keeping a map keyed by seat id
 * means a reconnection that misses messages costs nothing beyond a stale seat
 * until the next change or the next reload, there is no queue to replay and
 * nothing to get out of order.
 */
@Injectable({ providedIn: 'root' })
export class SeatStream {
  private readonly zone = inject(NgZone);

  private readonly changes = signal<ReadonlyMap<number, SeatChanged>>(new Map());
  private source?: EventSource;
  private watchedEventId?: string;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  /** The latest status seen for each seat, as a signal. */
  latest(): Signal<ReadonlyMap<number, SeatChanged>> {
    return this.changes.asReadonly();
  }

  /** Convenience for a single seat. */
  statusOf(eventSeatId: number): Signal<SeatStatus | undefined> {
    return computed(() => this.changes().get(eventSeatId)?.status);
  }

  watch(eventId: string): void {
    if (this.watchedEventId === eventId && this.source) {
      return;
    }
    this.stop();

    this.watchedEventId = eventId;
    this.changes.set(new Map());

    // Outside the Angular zone: every message would otherwise run a global
    // change detection pass, and a busy event can produce a lot of messages.
    // Writing to the signal below schedules exactly the updates that are needed.
    this.zone.runOutsideAngular(() => {
      const source = new EventSource(`/api/events/${eventId}/seats/stream`);
      source.addEventListener('seat', (message) => this.apply(message));
      this.source = source;
    });
  }

  stop(): void {
    this.source?.close();
    this.source = undefined;
    this.watchedEventId = undefined;
  }

  private apply(message: MessageEvent<string>): void {
    let change: SeatChanged;
    try {
      change = JSON.parse(message.data) as SeatChanged;
    } catch {
      return;
    }

    // A new Map each time: mutating the old one would not change the signal's
    // identity, and nothing would re-render.
    const next = new Map(this.changes());
    next.set(change.eventSeatId, change);
    this.changes.set(next);
  }
}
