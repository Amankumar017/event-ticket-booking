import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
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

describe('SeatMapPage holding seats', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SeatMapPage],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
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
    expect(summary?.textContent).toContain('Holding 1 seat');
    expect(html(fixture).querySelector('.summary__clock')?.textContent).toMatch(/^[45]:\d{2}$/);
  });

  it('offers confirm and release while the hold is live', async () => {
    const fixture = await render();

    await holdA1(fixture, heldBooking());

    const buttons = Array.from(html(fixture).querySelectorAll('.summary__action')).map((button) =>
      button.textContent?.trim(),
    );
    expect(buttons).toEqual(['Release', 'Confirm']);
  });

  it('reports the reference once the booking is confirmed', async () => {
    const fixture = await render();
    await holdA1(fixture, heldBooking());

    click(fixture, '.summary__action--primary');
    httpMock
      .expectOne('/api/bookings/SEAT-ABCD2345/confirmation')
      .flush(heldBooking({ status: 'CONFIRMED', expiresAt: null, confirmedAt: '2026-08-15T12:00:00Z' }));
    await fixture.whenStable();
    fixture.detectChanges();
    httpMock.expectOne('/api/events/7/seats').flush(SEAT_MAP);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(html(fixture).querySelector('.summary__text')?.textContent).toContain('SEAT-ABCD2345');
  });

  /** A hold whose clock ran out must not offer a button the server would refuse. */
  it('replaces the confirm button when the hold has already lapsed', async () => {
    const fixture = await render();

    await holdA1(fixture, heldBooking({ expiresAt: new Date(Date.now() - 1000).toISOString() }));

    expect(html(fixture).querySelector('.summary__text')?.textContent).toContain('has expired');
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
