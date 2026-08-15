import { Routes } from '@angular/router';
import { signedInGuard } from './core/signed-in-guard';

/**
 * Every feature is lazily loaded. On an app this size it changes nothing
 * measurable, but it is the habit that keeps the initial bundle flat as
 * features are added.
 */
export const routes: Routes = [
  {
    path: '',
    title: 'Seatly · what’s on',
    loadComponent: () => import('./features/event-list/event-list').then((m) => m.EventList),
  },
  {
    path: 'events/:eventId',
    title: 'Seatly · choose your seats',
    loadComponent: () => import('./features/seat-map/seat-map').then((m) => m.SeatMapPage),
  },
  {
    path: 'sign-in',
    title: 'Seatly · sign in',
    loadComponent: () => import('./features/sign-in/sign-in').then((m) => m.SignIn),
  },
  {
    path: 'my-bookings',
    title: 'Seatly · your bookings',
    canActivate: [signedInGuard],
    loadComponent: () => import('./features/my-bookings/my-bookings').then((m) => m.MyBookings),
  },
  { path: '**', redirectTo: '' },
];
