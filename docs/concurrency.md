# Selling one seat to eight people

Eight ordinary callers ask for seat A1 at the same instant. Everything below is
measured output, reproduced identically on every run. Nothing is simulated: no
injected delays, no paused threads, no test-only hooks in the production code.

```bash
cd backend
./mvnw test -Dtest=BookingUnderContentionTest
```

| Strategy                          | Sold | Turned away | Errors | Live claims |
| --------------------------------- | ---- | ----------- | ------ | ----------- |
| No lock, inserts flushed first    | 1    | 0           | **7**  | 1           |
| No lock, seat update first        | 8    | 0           | 0      | **8**       |
| Pessimistic row lock *(shipped)*  | 1    | 7           | 0      | 1           |
| Optimistic version check          | 1    | 7           | 0      | 1           |
| No lock, unique index only        | 1    | 7           | 0      | 1           |

The first two rows are the unlocked implementation, measured at commit
`319dc47`; the constraint added in `V3` now prevents the second outcome from
being reachable at all. The rest are measured at HEAD.

## The implementation that failed

The obvious one, and what this project shipped at commit `319dc47`. It passes
all seven single-threaded tests in `BookingServiceTests`.

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

## What the two failures have in common

Fixing the deadlock made the system worse. The crash was loud, immediate, and
impossible to ship without noticing. The silent double-sale looks like a healthy
service and surfaces as two people holding tickets for the same chair at the
door.

Both have the same cause: a decision made from a row that nothing was holding.
Statement order decides which of the two you get; it does not decide whether the
bug exists.

## The fix: lock first, then decide

```java
List<Long> seatIds = request.eventSeatIds().stream().distinct().sorted().toList();

List<EventSeat> seats = eventSeats.lockAllById(seatIds);   // the lock
for (EventSeat seat : seats) {
    if (!seat.isClaimableAt(now)) { throw new SeatUnavailableException(...); }
}                                                          // then the check
```

```
sold the seat            : 1
turned away              : 7
failures                 : 0
live claims on the seat  : 1
```

Seven customers are told the seat is taken, which is true, and no one sees an
error. The competing transactions block inside `lockAllById` and read the seat
only once the winner has committed.

### What Hibernate actually emits

```sql
select ... from event_seat es1_0
where es1_0.id in (?, ?)
order by es1_0.id
for no key update of es1_0
```

Two details in that statement carry the whole fix.

**`for no key update`, not `for update`.** This is Hibernate's PostgreSQL
rendering of `PESSIMISTIC_WRITE`, and it is weaker on purpose: it excludes other
bookers but still permits the `for key share` locks that foreign key checks
take. That is precisely the lock the `booking_seat` insert needs on this row —
so the cycle that deadlocked the unlocked version cannot form. A stricter
`for update` would have reintroduced it.

**`order by es1_0.id`.** Two bookings for the overlapping sets `{A, B}` and
`{B, A}` would otherwise take their locks in opposite orders and deadlock. Any
fixed order works, as long as every caller agrees on it. Measured, with the two
requests submitted in opposite orders:

```
sold the seat : 1
turned away   : 1
failures      : 0
```

## Why not optimistic locking

It works — one seller, seven losers, no double sale. The difference is what the
losers spend before finding out. An optimistic caller does the entire booking
and then discards it; a pessimistic one waits and does the work once. On a single
row that many people want simultaneously, waiting is cheaper and the answer is
clearer.

The `@Version` column stays anyway. It costs nothing and it guards the write
paths that do not take a lock — the hold-expiry job in stage 6 among them.

## The database's own guarantee

Application correctness is one layer. `V3` adds another:

```sql
create unique index booking_seat_one_live_claim_per_seat
    on booking_seat (event_seat_id)
    where active;
```

Partial, not a plain unique constraint. A cancelled booking has to give its
chair back, and a constraint counting dead claims would mean deleting the row
recording who once held the seat before it could be resold. `active` keeps the
history and the invariant at once.

Run the unlocked sequence against it and every claim after the first is thrown
out by the database:

```
sold the seat            : 1
turned away              : 7   (rejected by the unique index)
live claims on the seat  : 1
```

Neither layer makes the other redundant. The lock gives customers a clear
answer; the index guarantees the invariant regardless of which code path, script
or future refactoring does the writing.
