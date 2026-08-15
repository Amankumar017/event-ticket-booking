import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Booking, HoldRequest, PaymentIntent } from './seatly-models';

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

  /**
   * Opens a payment for a held booking.
   *
   * The idempotency key is generated once per attempt and travels with every
   * retry of that attempt. Without it, a request that times out leaves the
   * client unable to tell whether a payment was opened, and trying again could
   * open a second one.
   */
  startPayment(bookingReference: string, idempotencyKey: string): Observable<PaymentIntent> {
    return this.http.post<PaymentIntent>(
      `/api/payments/intents/${bookingReference}`,
      {},
      { headers: { 'Idempotency-Key': idempotencyKey } },
    );
  }

  /**
   * Stands in for the customer completing payment at the provider.
   *
   * Only exists while the backend runs under its seed profile. A real checkout
   * would hand off to the provider's page and wait for the webhook.
   */
  settlePayment(paymentReference: string, outcome: 'succeeded' | 'failed'): Observable<unknown> {
    return this.http.post(`/api/dev/payments/${paymentReference}/settle?outcome=${outcome}`, {});
  }

  byReference(reference: string): Observable<Booking> {
    return this.http.get<Booking>(`/api/bookings/${reference}`);
  }

  cancel(reference: string): Observable<Booking> {
    return this.http.post<Booking>(`/api/bookings/${reference}/cancellation`, {});
  }
}
