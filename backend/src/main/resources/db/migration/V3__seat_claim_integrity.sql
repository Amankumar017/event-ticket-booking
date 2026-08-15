-- Make a double claim impossible, in two independent ways.
--
-- The application stops it by locking the seat row before deciding anything.
-- The database stops it whether or not the application got that right. Neither
-- makes the other redundant: the lock gives customers a clear answer, and the
-- constraint is what guarantees the invariant no matter which code path, script
-- or future refactoring does the writing.

-- Optimistic locking support. Hibernate stamps this on every update and refuses
-- one whose expected version has moved on. It matters for write paths that do
-- not take a lock -- the expiry job in stage 6, for one.
alter table event_seat
    add column version bigint not null default 0;

-- A booking line stops being a claim on the chair once the booking behind it is
-- cancelled or expires.
alter table booking_seat
    add column active boolean not null default true;

-- The invariant: at most one live claim per seat.
--
-- A plain unique constraint on event_seat_id would be wrong. It would also
-- count claims from cancelled bookings, so releasing a seat would mean deleting
-- the row that records who once held it -- and the chair could never be resold
-- without destroying that history. The partial index only sees live claims.
create unique index booking_seat_one_live_claim_per_seat
    on booking_seat (event_seat_id)
    where active;
