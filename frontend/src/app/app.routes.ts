import { Routes } from '@angular/router';

/**
 * Both feature components are lazily loaded. On an app this size it changes
 * nothing measurable, but it is the habit that keeps the initial bundle flat as
 * features are added.
 */
export const routes: Routes = [
  {
    path: '',
    title: 'Seatly — what’s on',
    loadComponent: () => import('./features/event-list/event-list').then((m) => m.EventList),
  },
  {
    path: 'events/:eventId',
    title: 'Seatly — choose your seats',
    loadComponent: () => import('./features/seat-map/seat-map').then((m) => m.SeatMapPage),
  },
  { path: '**', redirectTo: '' },
];
