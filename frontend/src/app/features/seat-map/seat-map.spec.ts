import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { SeatStream } from '../../core/seat-stream';
import { SeatMapPage } from './seat-map';
import { SeatMap } from '../../core/seatly-models';

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
            { eventSeatId: 2, number: 2, label: 'A2', status: 'SOLD', priceMinor: 120_000 },
            { eventSeatId: 3, number: 3, label: 'A3', status: 'HELD', priceMinor: 120_000 },
          ],
        },
      ],
    },
    {
      name: 'Balcony',
      displayOrder: 2,
      rows: [
        {
          label: 'E',
          seats: [
            { eventSeatId: 4, number: 1, label: 'E1', status: 'AVAILABLE', priceMinor: 60_000 },
          ],
        },
      ],
    },
  ],
};

/** The seat map opens a live stream; these tests are not about that. */
const seatStreamStub = {
  latest: () => signal(new Map()),
  statusOf: () => signal(undefined),
  watch: () => {},
  stop: () => {},
};

describe('SeatMapPage', () => {
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
  });

  afterEach(() => httpMock.verify());

  /**
   * Renders the component with the route input set, then answers its request.
   *
   * {@link TestBed.tick} rather than `whenStable`: the resource issues its
   * request from an effect, and awaiting stability first lets the resource tear
   * that request down before the test can answer it.
   */
  async function render(): Promise<ComponentFixture<SeatMapPage>> {
    const fixture = TestBed.createComponent(SeatMapPage);
    fixture.componentRef.setInput('eventId', '7');
    fixture.detectChanges();
    TestBed.tick();

    // Answer first, settle second. The resource publishes its value on a
    // microtask after the response arrives, so the wait belongs here and not
    // before the flush, awaiting earlier tears the request down unanswered.
    httpMock.expectOne('/api/events/7/seats').flush(SEAT_MAP);
    await fixture.whenStable();
    fixture.detectChanges();

    return fixture;
  }

  function seats(fixture: ComponentFixture<SeatMapPage>): HTMLButtonElement[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button.seat'));
  }

  it('draws every seat in the map', async () => {
    const fixture = await render();

    expect(seats(fixture).length).toBe(4);
    expect(seats(fixture)[0].textContent?.trim()).toBe('1');
  });

  it('only lets an available seat be clicked', async () => {
    const fixture = await render();
    const [available, sold, held] = seats(fixture);

    expect(available.disabled).toBeFalse();
    expect(sold.disabled).toBeTrue();
    expect(held.disabled).toBeTrue();
    expect(sold.classList).toContain('seat--sold');
    expect(held.classList).toContain('seat--held');
  });

  it('totals the seats a customer picks', async () => {
    const fixture = await render();

    seats(fixture)[0].click(); // A1, Rs 1,200
    seats(fixture)[3].click(); // E1, Rs 600
    fixture.detectChanges();
    TestBed.tick();

    const summary = (fixture.nativeElement as HTMLElement).querySelector('.summary__text');

    // The counter names the seats rather than counting them: "A1, E1" is what
    // the customer is about to be handed.
    expect(summary?.textContent).toContain('A1');
    expect(summary?.textContent).toContain('E1');
    expect(summary?.textContent).toContain('1,800');
  });

  it('deselects a seat when it is clicked again', async () => {
    const fixture = await render();

    seats(fixture)[0].click();
    fixture.detectChanges();
    expect(seats(fixture)[0].classList).toContain('seat--selected');

    seats(fixture)[0].click();
    fixture.detectChanges();
    TestBed.tick();

    expect(seats(fixture)[0].classList).not.toContain('seat--selected');
    expect((fixture.nativeElement as HTMLElement).querySelector('.summary')).toBeNull();
  });
});
