import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Booking, HoldRequest } from './seatly-models';

/**
 * Writes go through HttpClient rather than httpResource.
 *
 * A resource models a value the page shows; holding, confirming and cancelling
 * are things the customer does, once each, at a moment of their choosing. Those
 * are calls, not state, and pretending otherwise means fighting the resource to
 * stop it re-issuing them.
 */
@Injectable({ providedIn: 'root' })
export class BookingApi {
  private readonly http = inject(HttpClient);

  hold(request: HoldRequest): Observable<Booking> {
    return this.http.post<Booking>('/api/bookings', request);
  }

  confirm(reference: string): Observable<Booking> {
    return this.http.post<Booking>(`/api/bookings/${reference}/confirmation`, {});
  }

  cancel(reference: string): Observable<Booking> {
    return this.http.post<Booking>(`/api/bookings/${reference}/cancellation`, {});
  }
}
