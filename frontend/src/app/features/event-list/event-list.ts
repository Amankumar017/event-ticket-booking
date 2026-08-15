import { ChangeDetectionStrategy, Component } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { EventSummary } from '../../core/seatly-models';

/**
 * What is on sale.
 *
 * The list is an {@link httpResource}: the request is described once, and its
 * loading, error and value states are signals. There is nothing to subscribe to
 * and nothing to unsubscribe from, which removes the most common source of leaks
 * in an Angular app.
 */
@Component({
  selector: 'app-event-list',
  imports: [RouterLink, DatePipe],
  templateUrl: './event-list.html',
  styleUrl: './event-list.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EventList {
  protected readonly events = httpResource<EventSummary[]>(() => '/api/events', {
    defaultValue: [],
  });
}
