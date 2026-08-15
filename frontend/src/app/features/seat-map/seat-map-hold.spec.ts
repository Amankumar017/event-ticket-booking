import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { Auth } from '../../core/auth';
import { SeatStream } from '../../core/seat-stream';
import { SeatMapPage } from './seat-map';
import { Booking, SeatMap } from '../../core/seatly-models';

const SEAT_MAP: SeatMap = {
  eventId: 7,
  title: 'An Evening of Hindustani Classical',
  venueName: 'Prithvi Playhouse',
  city: 'Mumbai',
  startsAt: '2026-09-05T13:30:00Z',
  currency: 'INR',
  sections: [
    {
      name: 'Stalls',
      displayOrder: 1,
      rows: [
        {
          label: 'A',
          seats: [
            { eventSeatId: 1, number: 1, label: 'A1', status: 'AVAILABLE', priceMinor: 120_000 },
            { eventSeatId: 2, number: 2, label: 'A2', status: 'AVAILABLE', priceMinor: 120_000 },
          ],
        },
      ],
    },
  ],
};

function heldBooking(overrides: Partial<Booking> = {}): Booking {
  return {
    reference: 'SEAT-ABCD2345',
    eventId: 7,
    status: 'PENDING',
    totalMinor: 120_000,
    currency: 'INR',
    expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    confirmedAt: null,
    seats: [{ eventSeatId: 1, label: 'A1', priceMinor: 120_000 }],
    ...overrides,
  };
}

/** The seat map opens a live stream; these tests are not about that. */
const seatStreamStub = {
  latest: () => signal(new Map()),
  statusOf: () => signal(undefined),
  watch: () => {},
  stop: () => {},
};

describe('SeatMapPage holding seats', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SeatMapPage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: SeatStream, useValue: seatStreamStub },
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);

    // Holding seats now requires an account. The anonymous case is covered by
    // the describe block at the bottom of this file.
    TestBed.inject(Auth).login('aman@example.com', 'correct-horse-battery').subscribe();
    httpMock.expectOne('/api/auth/login').flush({
      accessToken: 'test-token',
      expiresInSeconds: 900,
      user: { id: 1, email: 'aman@example.com', displayName: 'Aman', role: 'CUSTOMER' },
    });
  });

  afterEach(() => httpMock.verify({ ignoreCancelled: true }));

  async function render(): Promise<ComponentFixture<SeatMapPage>> {
    const fixture = TestBed.createComponent(SeatMapPage);
    fixture.componentRef.setInput('eventId', '7');
    fixture.detectChanges();
    TestBed.tick();

    httpMock.expectOne('/api/events/7/seats').flush(SEAT_MAP);
    await fixture.whenStable();
    fixture.detectChanges();

    return fixture;
  }

  function html(fixture: ComponentFixture<SeatMapPage>): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function click(fixture: ComponentFixture<SeatMapPage>, selector: string): void {
    html(fixture).querySelector<HTMLButtonElement>(selector)!.click();
    fixture.detectChanges();
  }

  /** Picks A1 and presses "Hold these seats", answering with the given booking. */
  async function holdA1(
    fixture: ComponentFixture<SeatMapPage>,
    booking: Booking,
  ): Promise<void> {
    click(fixture, 'button.seat');
    click(fixture, '.summary__action--primary');

    const request = httpMock.expectOne('/api/bookings');
    expect(request.request.body.eventSeatIds).toEqual([1]);
    request.flush(booking);

    await fixture.whenStable();
    fixture.detectChanges();

    // The component reloads the chart after every write.
    httpMock.expectOne('/api/events/7/seats').flush(SEAT_MAP);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('shows a countdown once the seats are held', async () => {
    const fixture = await render();

    await holdA1(fixture, heldBooking());

    const summary = html(fixture).querySelector('.summary__text');
    expect(summary?.textContent).toContain('A1');
    expect(summary?.textContent).toContain('left to pay');
    expect(html(fixture).querySelector('.summary__clock')?.textContent).toMatch(/^[45]:\d{2}$/);
  });

  it('offers pay and release while the hold is live', async () => {
    const fixture = await render();

    await holdA1(fixture, heldBooking());

    const buttons = Array.from(html(fixture).querySelectorAll('.summary__action')).map((button) =>
      button.textContent?.trim(),
    );
    expect(buttons).toEqual(['Release', 'Pay ₹1,200']);
  });

  /**
   * Paying is three calls: open the payment, settle it at the provider, then
   * read back the booking the webhook has confirmed.
   */
  it('reports the reference once the booking is paid for', async () => {
    const fixture = await render();
    await holdA1(fixture, heldBooking());

    click(fixture, '.summary__action--primary');

    const intent = httpMock.expectOne('/api/payments/intents/SEAT-ABCD2345');
    expect(intent.request.headers.get('Idempotency-Key')).toBeTruthy();
    intent.flush({
      paymentReference: 'pay_abc123',
      bookingReference: 'SEAT-ABCD2345',
      amountMinor: 120_000,
      currency: 'INR',
      status: 'REQUIRES_PAYMENT',
      failureReason: null,
      settledAt: null,
    });
    await fixture.whenStable();

    httpMock.expectOne('/api/dev/payments/pay_abc123/settle?outcome=succeeded').flush({ acted: true });
    await fixture.whenStable();

    httpMock
      .expectOne('/api/bookings/SEAT-ABCD2345')
      .flush(heldBooking({ status: 'CONFIRMED', expiresAt: null, confirmedAt: '2026-08-15T12:00:00Z' }));
    await fixture.whenStable();
    fixture.detectChanges();
    httpMock.expectOne('/api/events/7/seats').flush(SEAT_MAP);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(html(fixture).querySelector('.summary__text')?.textContent).toContain('SEAT-ABCD2345');
  });

  /** The same key on a retry is what stops a timeout becoming two payments. */
  it('reuses the idempotency key when a payment attempt fails', async () => {
    const fixture = await render();
    await holdA1(fixture, heldBooking());

    click(fixture, '.summary__action--primary');
    const first = httpMock.expectOne('/api/payments/intents/SEAT-ABCD2345');
    const key = first.request.headers.get('Idempotency-Key');
    first.flush(null, { status: 504, statusText: 'Gateway Timeout' });
    await fixture.whenStable();
    fixture.detectChanges();
    httpMock.expectOne('/api/events/7/seats').flush(SEAT_MAP);
    await fixture.whenStable();
    fixture.detectChanges();

    click(fixture, '.summary__action--primary');
    const retry = httpMock.expectOne('/api/payments/intents/SEAT-ABCD2345');

    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    retry.flush(null, { status: 504, statusText: 'Gateway Timeout' });
    await fixture.whenStable();
    fixture.detectChanges();
    httpMock.expectOne('/api/events/7/seats').flush(SEAT_MAP);
  });

  /** A hold whose clock ran out must not offer a button the server would refuse. */
  it('replaces the confirm button when the hold has already lapsed', async () => {
    const fixture = await render();

    await holdA1(fixture, heldBooking({ expiresAt: new Date(Date.now() - 1000).toISOString() }));

    expect(html(fixture).querySelector('.summary__text')?.textContent).toContain('ran out');
    expect(html(fixture).querySelector('.summary__action--primary')).toBeNull();
  });

  /** The server's own words, not a status code the customer has to interpret. */
  it('shows the problem detail when the seat has gone', async () => {
    const fixture = await render();

    click(fixture, 'button.seat');
    click(fixture, '.summary__action--primary');
    httpMock.expectOne('/api/bookings').flush(
      { type: 'https://seatly.dev/problems/seat-unavailable', detail: 'Seat A1 is no longer available' },
      { status: 409, statusText: 'Conflict' },
    );
    await fixture.whenStable();
    fixture.detectChanges();
    httpMock.expectOne('/api/events/7/seats').flush(SEAT_MAP);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(html(fixture).querySelector('.state--error')?.textContent).toContain(
      'Seat A1 is no longer available',
    );
  });
});

describe('SeatMapPage when nobody is signed in', () => {
  let httpMock: HttpTestingController;
  let router: Router;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SeatMapPage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: SeatStream, useValue: seatStreamStub },
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
  });

  afterEach(() => httpMock.verify({ ignoreCancelled: true }));

  /**
   * The redirect is a courtesy, not a control. The server refuses an
   * unauthenticated hold whatever the browser does.
   */
  it('sends the visitor to sign in rather than attempting a hold', async () => {
    const fixture = TestBed.createComponent(SeatMapPage);
    fixture.componentRef.setInput('eventId', '7');
    fixture.detectChanges();
    TestBed.tick();
    httpMock.expectOne('/api/events/7/seats').flush(SEAT_MAP);
    await fixture.whenStable();
    fixture.detectChanges();

    const html = fixture.nativeElement as HTMLElement;
    html.querySelector<HTMLButtonElement>('button.seat')!.click();
    fixture.detectChanges();
    html.querySelector<HTMLButtonElement>('.summary__action--primary')!.click();
    fixture.detectChanges();

    expect(router.navigate).toHaveBeenCalledWith(['/sign-in'], {
      queryParams: { returnTo: '/events/7' },
    });
    httpMock.expectNone('/api/bookings');
  });
});
