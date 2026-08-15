import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Auth, Session } from './auth';
import { authInterceptor } from './auth-interceptor';

const SESSION: Session = {
  accessToken: 'fresh-token',
  expiresInSeconds: 900,
  user: { id: 1, email: 'aman@example.com', displayName: 'Aman', role: 'CUSTOMER' },
};

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let auth: Auth;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });

    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(Auth);
  });

  afterEach(() => httpMock.verify());

  /** Signs in through the real code path so the token is held the usual way. */
  function signIn(token = 'first-token'): void {
    auth.login('aman@example.com', 'correct-horse-battery').subscribe();
    httpMock.expectOne('/api/auth/login').flush({ ...SESSION, accessToken: token });
  }

  it('sends no Authorization header when nobody is signed in', () => {
    http.get('/api/events').subscribe();

    expect(httpMock.expectOne('/api/events').request.headers.has('Authorization')).toBeFalse();
  });

  it('attaches the access token once signed in', () => {
    signIn();

    http.get('/api/bookings/mine').subscribe();

    expect(httpMock.expectOne('/api/bookings/mine').request.headers.get('Authorization')).toBe(
      'Bearer first-token',
    );
  });

  it('refreshes and retries when the token has expired', () => {
    signIn();
    let result: unknown;
    http.get('/api/bookings/mine').subscribe((response) => (result = response));

    httpMock.expectOne('/api/bookings/mine').flush(null, { status: 401, statusText: 'Unauthorized' });
    httpMock.expectOne('/api/auth/refresh').flush(SESSION);

    const retry = httpMock.expectOne('/api/bookings/mine');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer fresh-token');
    retry.flush({ ok: true });
    expect(result).toEqual({ ok: true } as never);
  });

  /**
   * The reason the queue exists. Refreshing per 401 would spend several refresh
   * tokens against a server that rotates them, and every one after the first
   * looks exactly like a replayed token -- which ends the session.
   */
  it('refreshes once for a burst of expired requests', () => {
    signIn();
    http.get('/api/bookings/mine').subscribe();
    http.get('/api/events/1/seats').subscribe();
    http.get('/api/auth/me').subscribe();

    httpMock.expectOne('/api/bookings/mine').flush(null, { status: 401, statusText: 'Unauthorized' });
    httpMock.expectOne('/api/events/1/seats').flush(null, { status: 401, statusText: 'Unauthorized' });
    httpMock.expectOne('/api/auth/me').flush(null, { status: 401, statusText: 'Unauthorized' });

    // Exactly one, however many requests failed.
    httpMock.expectOne('/api/auth/refresh').flush(SESSION);

    const retried = httpMock.match(
      (request) => request.headers.get('Authorization') === 'Bearer fresh-token',
    );
    expect(retried.length).toBe(3);
    retried.forEach((request) => request.flush({}));
  });

  it('gives up and forgets the session when the refresh itself fails', () => {
    signIn();
    let failed = false;
    http.get('/api/bookings/mine').subscribe({ error: () => (failed = true) });

    httpMock.expectOne('/api/bookings/mine').flush(null, { status: 401, statusText: 'Unauthorized' });
    httpMock.expectOne('/api/auth/refresh').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(failed).toBeTrue();
    expect(auth.isSignedIn()).toBeFalse();
    expect(auth.token()).toBeNull();
  });

  /** A wrong password is not an expired token, and must not trigger a refresh. */
  it('does not try to refresh a failed sign-in', () => {
    let failed = false;
    auth.login('aman@example.com', 'wrong').subscribe({ error: () => (failed = true) });

    httpMock.expectOne('/api/auth/login').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(failed).toBeTrue();
    httpMock.expectNone('/api/auth/refresh');
  });

  it('does not try to refresh when nobody was signed in', () => {
    let failed = false;
    http.get('/api/bookings/mine').subscribe({ error: () => (failed = true) });

    httpMock.expectOne('/api/bookings/mine').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(failed).toBeTrue();
    httpMock.expectNone('/api/auth/refresh');
  });
});
