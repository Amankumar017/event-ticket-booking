import { TestBed } from '@angular/core/testing';
import { SeatStream } from './seat-stream';

/**
 * A stand-in for the browser's EventSource, so these tests can deliver messages
 * without a server.
 */
class FakeEventSource {
  static instances: FakeEventSource[] = [];

  readonly listeners = new Map<string, (event: MessageEvent<string>) => void>();
  closed = false;

  constructor(readonly url: string) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(name: string, listener: (event: MessageEvent<string>) => void): void {
    this.listeners.set(name, listener);
  }

  close(): void {
    this.closed = true;
  }

  deliver(data: unknown): void {
    this.listeners.get('seat')?.({ data: JSON.stringify(data) } as MessageEvent<string>);
  }

  deliverRaw(data: string): void {
    this.listeners.get('seat')?.({ data } as MessageEvent<string>);
  }
}

describe('SeatStream', () => {
  let stream: SeatStream;
  let originalEventSource: unknown;

  beforeEach(() => {
    FakeEventSource.instances = [];
    originalEventSource = (globalThis as Record<string, unknown>)['EventSource'];
    (globalThis as Record<string, unknown>)['EventSource'] = FakeEventSource;

    TestBed.configureTestingModule({});
    stream = TestBed.inject(SeatStream);
  });

  afterEach(() => {
    stream.stop();
    (globalThis as Record<string, unknown>)['EventSource'] = originalEventSource;
  });

  /** The signal's current value. */
  function latest() {
    return stream.latest()();
  }

  function connection(): FakeEventSource {
    return FakeEventSource.instances[FakeEventSource.instances.length - 1];
  }

  it('subscribes to the stream for the event being watched', () => {
    stream.watch('7');

    expect(connection().url).toBe('/api/events/7/seats/stream');
  });

  it('records the latest status for each seat', () => {
    stream.watch('7');

    connection().deliver({ eventId: 7, eventSeatId: 1, status: 'HELD', heldUntil: null });
    connection().deliver({ eventId: 7, eventSeatId: 2, status: 'SOLD', heldUntil: null });

    expect(latest().get(1)?.status).toBe('HELD');
    expect(latest().get(2)?.status).toBe('SOLD');
  });

  /** A seat that changes twice keeps only what it is now. */
  it('replaces an earlier status with a later one', () => {
    stream.watch('7');

    connection().deliver({ eventId: 7, eventSeatId: 1, status: 'HELD', heldUntil: null });
    connection().deliver({ eventId: 7, eventSeatId: 1, status: 'AVAILABLE', heldUntil: null });

    expect(latest().get(1)?.status).toBe('AVAILABLE');
    expect(latest().size).toBe(1);
  });

  it('gives the map a new identity so signals notice', () => {
    stream.watch('7');
    const before = latest();

    connection().deliver({ eventId: 7, eventSeatId: 1, status: 'HELD', heldUntil: null });

    expect(latest()).not.toBe(before);
  });

  it('closes the old connection when the event changes', () => {
    stream.watch('7');
    const first = connection();

    stream.watch('9');

    expect(first.closed).toBeTrue();
    expect(connection().url).toBe('/api/events/9/seats/stream');
  });

  /** Watching the same event again must not open a second connection. */
  it('does not reconnect for the event it is already watching', () => {
    stream.watch('7');
    stream.watch('7');

    expect(FakeEventSource.instances.length).toBe(1);
  });

  it('starts from nothing when it moves to another event', () => {
    stream.watch('7');
    connection().deliver({ eventId: 7, eventSeatId: 1, status: 'SOLD', heldUntil: null });

    stream.watch('9');

    expect(latest().size).toBe(0);
  });

  it('ignores a message it cannot read', () => {
    stream.watch('7');

    connection().deliverRaw('not json');

    expect(latest().size).toBe(0);
  });
});
