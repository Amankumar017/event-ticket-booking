import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { Auth } from './auth';

/**
 * Endpoints that must not be retried through the refresh path.
 *
 * A 401 from the login endpoint means the password was wrong, and answering it
 * by refreshing would turn a failed sign-in into an infinite loop.
 */
const AUTH_ENDPOINTS = [
  '/api/auth/login',
  '/api/auth/register',
  '/api/auth/refresh',
  '/api/auth/logout',
];

/**
 * Attaches the access token, and renews it once when the server says it has
 * expired.
 *
 * The queueing that stops a burst of 401s from spending several refresh tokens
 * lives in {@link Auth.refreshOnce}.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(Auth);

  if (AUTH_ENDPOINTS.some((endpoint) => request.url.startsWith(endpoint))) {
    return next(request);
  }

  return next(withToken(request, auth.token())).pipe(
    catchError((failure: unknown) => {
      // Only a request that carried a token can have had one expire. A 401 on an
      // anonymous call means the endpoint needs signing in for, which refreshing
      // will not fix.
      const tokenMayHaveExpired =
        failure instanceof HttpErrorResponse && failure.status === 401 && auth.token() !== null;

      if (!tokenMayHaveExpired) {
        return throwError(() => failure);
      }

      return auth.refreshOnce().pipe(switchMap((token) => next(withToken(request, token))));
    }),
  );
};

function withToken(request: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  return token ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : request;
}
