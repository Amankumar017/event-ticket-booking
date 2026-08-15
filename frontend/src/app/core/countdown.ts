import { DestroyRef, Injectable, NgZone, Signal, computed, inject, signal } from '@angular/core';

/**
 * A signal that counts down to a deadline.
 *
 * One interval for the whole application rather than one per component, started
 * the first time anybody asks and cleared when the injector is destroyed. The
 * tick is deliberately coarse: a countdown shown in whole seconds has no use for
 * anything finer, and a timer firing four times a second to redraw the same
 * digits is just heat.
 */
@Injectable({ providedIn: 'root' })
export class Countdown {
  private readonly zone = inject(NgZone);
  private readonly now = signal(Date.now());
  private ticker?: ReturnType<typeof setInterval>;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      clearInterval(this.ticker);
      this.ticker = undefined;
    });
  }

  /**
   * Seconds remaining until `deadline`, never below zero.
   *
   * @param deadline ISO-8601 instant, or null when there is nothing to count
   */
  secondsUntil(deadline: Signal<string | null>): Signal<number> {
    // Outside the Angular zone, deliberately. A timer inside it would run a
    // global change detection pass every second whether or not anything moved,
    // and would leave a test fixture permanently unstable -- zone.js counts a
    // repeating interval as work still in flight, so whenStable() never
    // resolves. Writing to a signal schedules its own update, so nothing is
    // lost by opting out.
    this.ticker ??= this.zone.runOutsideAngular(() =>
      setInterval(() => this.now.set(Date.now()), 1000),
    );

    return computed(() => {
      const at = deadline();
      if (!at) {
        return 0;
      }
      return Math.max(0, Math.round((Date.parse(at) - this.now()) / 1000));
    });
  }

  /** Formats a second count as m:ss. */
  static format(seconds: number): string {
    const minutes = Math.floor(seconds / 60);
    const rest = seconds % 60;
    return `${minutes}:${rest.toString().padStart(2, '0')}`;
  }
}
