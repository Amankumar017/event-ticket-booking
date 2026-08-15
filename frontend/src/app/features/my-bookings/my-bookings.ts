import { ChangeDetectionStrategy, Component } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MinorCurrencyPipe } from '../../core/minor-currency-pipe';
import { Booking } from '../../core/seatly-models';

@Component({
  selector: 'app-my-bookings',
  imports: [RouterLink, DatePipe, MinorCurrencyPipe],
  templateUrl: './my-bookings.html',
  styleUrl: './my-bookings.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MyBookings {
  protected readonly bookings = httpResource<Booking[]>(() => '/api/bookings/mine', {
    defaultValue: [],
  });
}
