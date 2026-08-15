# Selling one seat to eight people

Everything below is measured output from `NaiveBookingUnderContentionTest`,
reproduced on every run. Nothing is simulated: no injected delays, no paused
threads, no test-only hooks in the production code. Eight ordinary callers ask
for seat A1 at the same instant.

```bash
cd backend
./mvnw test -Dtest=NaiveBookingUnderContentionTest
```

## The implementation under test

The obvious one. It passes all seven single-threaded tests in
`BookingServiceTests`.

```java
for (EventSeat seat : seats) {
    if (!seat.isClaimableAt(now)) {
        throw new SeatUnavailableException(...);   // the check
    }
}

Booking booking = new Booking(...);                // and the write
seats.forEach(booking::addSeat);
bookings.save(booking);
seats.forEach(EventSeat::markSold);
```

Nothing holds the seat between the check and the write. Under PostgreSQL's
default READ COMMITTED isolation all eight callers run the check before any of
them writes, and all eight conclude the seat is free.

## Outcome one: as written

```
sold the seat            : 1
politely refused         : 0
killed by deadlock (500) : 7
bookings holding the seat: 1
```

Seven customers out of eight get an internal server error.

Hibernate flushes the `booking` and `booking_seat` inserts before the
`event_seat` update — the order is chosen by the persistence context, not by the
order of the statements in the method. Inserting into `booking_seat` takes a
foreign-key lock on the `event_seat` row it references; the update then needs
that same row exclusively, while every other transaction is holding a
foreign-key lock on it. PostgreSQL finds the cycle and kills all but one:

```
ERROR: deadlock detected
  Detail: Process 70 waits for ShareLock on transaction 751; blocked by process 63.
          Process 63 waits for ShareLock on transaction 752; blocked by process 70.
  Where: while locking tuple (0,1) in relation "event_seat"
```

SQLSTATE 40P01, surfaced by Spring as `CannotAcquireLockException`.

The seat is not oversold. The service is simply unusable the moment two people
want the same seat.

## Outcome two: the same logic, writes reordered

Send the seat update before the inserts — what you get from an explicit flush, or
from writing the update as a query instead of through the persistence context.
The deadlock disappears, because the transactions now queue on a single exclusive
row lock instead of waiting on each other.

```
told "the seat is yours" : 8
politely refused         : 0
failures of any kind     : 0
bookings holding the seat: 8
seats sold more than once: 1
```

Eight confirmed bookings. One chair. Zero errors, zero warnings, nothing unusual
in the log. Each transaction blocks on the row lock, waits its turn, re-reads the
row after the previous one commits — and then applies an update whose `WHERE`
clause only names the primary key, so the decision it made before it waited is
never revisited.

The audit query that finds it:

```sql
select event_seat_id, count(*)
from booking_seat
group by event_seat_id
having count(*) > 1;
```

On a correct system this returns nothing, always.

## What this actually shows

Fixing the deadlock made the system worse. The crash was loud, immediate, and
impossible to ship without noticing. The silent double-sale looks like a healthy
service and surfaces as two people holding tickets for the same chair at the
door.

Both failures have the same cause: a decision made from a row that nothing was
holding. Statement order decides which of the two you get; it does not decide
whether the bug exists.
