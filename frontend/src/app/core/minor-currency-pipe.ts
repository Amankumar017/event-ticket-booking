import { Pipe, PipeTransform } from '@angular/core';

/**
 * Formats a price held in minor units.
 *
 * The one place in the app where money is divided by 100. Everything else --
 * totals, comparisons, what gets sent back to the server, stays in paise,
 * because that is the only representation that adds up exactly.
 */
@Pipe({ name: 'minorCurrency' })
export class MinorCurrencyPipe implements PipeTransform {
  transform(minor: number | undefined, currency = 'INR'): string {
    if (minor == null) {
      return '';
    }
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency,
      maximumFractionDigits: 0,
    }).format(minor / 100);
  }
}
