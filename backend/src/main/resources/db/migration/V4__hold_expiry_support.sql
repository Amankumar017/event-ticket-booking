-- Support for finding and releasing lapsed holds.

-- The expiry job asks one question on a schedule: which seats are held past
-- their deadline? A partial index answers it by reading only the held rows,
-- which stay a tiny fraction of the table even when an event sells out.
create index event_seat_held_until_deadline
    on event_seat (held_until)
    where status = 'HELD';

-- Same shape for the bookings behind those holds. The existing
-- booking_by_status_and_expiry index covers every status; this one covers only
-- the rows the job actually scans.
create index booking_pending_expiry
    on booking (expires_at)
    where status = 'PENDING';
