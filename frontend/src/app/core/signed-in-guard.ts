import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { Auth } from './auth';

/**
 * Lets a signed-in visitor through, and tries the refresh cookie for everybody
 * else before giving up.
 *
 * The access token only lives in memory, so a reload always starts signed out as
 * far as this application knows. Asking the server first is what turns "the tab
 * was refreshed" into "still signed in" rather than into a login screen.
 *
 * This guard decides what a person may *see*. It is not what protects anything:
 * the server checks every request regardless, and would refuse a booking from an
 * unauthenticated caller whatever the browser believes.
 */
export const signedInGuard: CanActivateFn = (_route, state) => {
  const auth = inject(Auth);
  const router = inject(Router);

  if (auth.isSignedIn()) {
    return true;
  }

  return auth.refresh().pipe(
    map(() => true),
    catchError(() =>
      // A query parameter rather than navigation state: it survives a full page
      // load, which is exactly the case that sends people here.
      of(router.createUrlTree(['/sign-in'], { queryParams: { returnTo: state.url } })),
    ),
  );
};
