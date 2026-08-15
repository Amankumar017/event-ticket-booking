import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { BehaviorSubject, Observable, catchError, filter, map, take, tap, throwError } from 'rxjs';

export type Role = 'CUSTOMER' | 'ORGANIZER' | 'ADMIN';

export interface CurrentUser {
  readonly id: number;
  readonly email: string;
  readonly displayName: string;
  readonly role: Role;
}

export interface Session {
  readonly accessToken: string;
  readonly expiresInSeconds: number;
  readonly user: CurrentUser;
}

/**
 * Who is signed in, and the token that proves it.
 *
 * <h2>The access token lives in memory, and only in memory</h2>
 *
 * Not localStorage, not sessionStorage, not a readable cookie. Anything a script
 * on the page can read, a script injected into the page can read too, and a
 * stolen access token is a valid session until it expires. Keeping it in a
 * signal means it dies with the tab.
 *
 * That costs a refresh on every page load, which is exactly what the
 * http-only refresh cookie is for: the browser sends it, script never sees it,
 * and a new access token comes back.
 */
@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly http = inject(HttpClient);

  private readonly accessToken = signal<string | null>(null);
  private readonly currentUser = signal<CurrentUser | null>(null);

  /**
   * Non-null while a refresh is in flight. Holds `null` until the new token
   * arrives, so waiters can filter for the first real value.
   */
  private refreshInFlight: BehaviorSubject<string | null> | null = null;

  readonly user = this.currentUser.asReadonly();
  readonly isSignedIn = computed(() => this.currentUser() !== null);

  token(): string | null {
    return this.accessToken();
  }

  login(email: string, password: string): Observable<Session> {
    return this.http
      .post<Session>('/api/auth/login', { email, password })
      .pipe(tap((session) => this.accept(session)));
  }

  register(email: string, password: string, displayName: string): Observable<Session> {
    return this.http
      .post<Session>('/api/auth/register', { email, password, displayName })
      .pipe(tap((session) => this.accept(session)));
  }

  /**
   * Trades the refresh cookie for a new access token.
   *
   * `withCredentials` matters: the cookie is set on a different origin during
   * development, and without this the browser simply will not send it.
   */
  refresh(): Observable<Session> {
    return this.http
      .post<Session>('/api/auth/refresh', {}, { withCredentials: true })
      .pipe(tap((session) => this.accept(session)));
  }

  /**
   * Refreshes once, however many callers ask at the same moment.
   *
   * A page that fires five requests at once gets five 401s at once when the
   * token expires. Refreshing per response would spend five refresh tokens
   * against a server that rotates them: the first would succeed and the rest
   * would look exactly like a replayed token, which the server treats as theft
   * and answers by ending the session. So the first caller starts the refresh
   * and the others wait for its result.
   */
  refreshOnce(): Observable<string> {
    if (this.refreshInFlight) {
      return this.refreshInFlight.pipe(
        filter((token): token is string => token !== null),
        take(1),
      );
    }

    const pending = new BehaviorSubject<string | null>(null);
    this.refreshInFlight = pending;

    return this.refresh().pipe(
      map((session) => {
        this.refreshInFlight = null;
        pending.next(session.accessToken);
        return session.accessToken;
      }),
      catchError((failure: unknown) => {
        // The refresh itself failed: the session is over. Everyone waiting is
        // released with the same error rather than left hanging forever.
        this.refreshInFlight = null;
        pending.error(failure);
        this.forget();
        return throwError(() => failure);
      }),
    );
  }

  logout(): Observable<void> {
    this.forget();
    return this.http.post<void>('/api/auth/logout', {}, { withCredentials: true });
  }

  /** Clears the local session without telling the server. */
  forget(): void {
    this.accessToken.set(null);
    this.currentUser.set(null);
  }

  private accept(session: Session): void {
    this.accessToken.set(session.accessToken);
    this.currentUser.set(session.user);
  }
}
