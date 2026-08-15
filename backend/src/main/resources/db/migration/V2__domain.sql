-- Domain schema.
--
-- The shape here is driven by one requirement: selling a numbered seat must be
-- decidable by looking at a single row. That row is event_seat -- the seat as it
-- exists for one particular event -- and it is the row every booking attempt
-- will contend on from stage 4 onwards.
--
-- Physical layout (venue -> seat_section -> seat) is separate from sellable
-- inventory (event -> event_seat) so that the same hall can host many events
-- without duplicating its seating chart.

create table venue (
    id          bigint generated always as identity primary key,
    name        varchar(160) not null,
    city        varchar(80)  not null,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now()
);

create table seat_section (
    id            bigint generated always as identity primary key,
    venue_id      bigint       not null references venue (id) on delete cascade,
    name          varchar(80)  not null,
    display_order integer      not null default 0,
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now(),
    constraint seat_section_name_unique_per_venue unique (venue_id, name)
);

create table seat (
    id          bigint generated always as identity primary key,
    section_id  bigint      not null references seat_section (id) on delete cascade,
    row_label   varchar(8)  not null,
    seat_number integer     not null,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    constraint seat_position_unique_per_section unique (section_id, row_label, seat_number),
    constraint seat_number_is_positive check (seat_number > 0)
);

create table event (
    id             bigint generated always as identity primary key,
    venue_id       bigint       not null references venue (id),
    title          varchar(200) not null,
    starts_at      timestamptz  not null,
    sales_open_at  timestamptz  not null,
    sales_close_at timestamptz  not null,
    status         varchar(20)  not null,
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now(),
    constraint event_status_is_known check (status in ('DRAFT', 'ON_SALE', 'CLOSED', 'CANCELLED')),
    constraint event_sales_window_is_ordered check (sales_open_at < sales_close_at)
);

-- One row per seat per event: the unit of inventory, and the unit of contention.
create table event_seat (
    id          bigint generated always as identity primary key,
    event_id    bigint      not null references event (id) on delete cascade,
    seat_id     bigint      not null references seat (id),
    price_minor bigint      not null,
    currency    varchar(3)  not null default 'INR',
    status      varchar(20) not null default 'AVAILABLE',
    held_until  timestamptz,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    constraint event_seat_unique_per_event unique (event_id, seat_id),
    constraint event_seat_status_is_known check (status in ('AVAILABLE', 'HELD', 'SOLD', 'BLOCKED')),
    constraint event_seat_price_is_not_negative check (price_minor >= 0),
    -- A hold without a deadline is a seat that never comes back.
    constraint event_seat_hold_has_deadline check (status <> 'HELD' or held_until is not null)
);

-- Drives the seat map query: "every seat for this event, with its current state".
create index event_seat_by_event_and_status on event_seat (event_id, status);

create table booking (
    id             bigint generated always as identity primary key,
    reference      varchar(16)  not null unique,
    event_id       bigint       not null references event (id),
    customer_name  varchar(120) not null,
    customer_email varchar(160) not null,
    status         varchar(20)  not null,
    total_minor    bigint       not null default 0,
    currency       varchar(3)   not null default 'INR',
    expires_at     timestamptz,
    confirmed_at   timestamptz,
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now(),
    constraint booking_status_is_known check (status in ('PENDING', 'CONFIRMED', 'EXPIRED', 'CANCELLED')),
    constraint booking_total_is_not_negative check (total_minor >= 0)
);

create index booking_by_status_and_expiry on booking (status, expires_at);

-- Money is stored in minor units (paise) as an integer. Never floating point:
-- 0.1 + 0.2 is not 0.3, and a ticket price that drifts by a paise per operation
-- becomes a reconciliation problem nobody wants to debug.
create table booking_seat (
    id            bigint generated always as identity primary key,
    booking_id    bigint      not null references booking (id) on delete cascade,
    event_seat_id bigint      not null references event_seat (id),
    price_minor   bigint      not null,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create index booking_seat_by_booking on booking_seat (booking_id);
create index booking_seat_by_event_seat on booking_seat (event_seat_id);

-- NOTE: booking_seat deliberately has no unique constraint on event_seat_id yet.
-- That constraint is the database's own guarantee that a seat cannot be sold
-- twice, and it arrives in stage 5 alongside the locking work. Adding it here
-- would mask the race condition stage 4 sets out to measure -- the point of the
-- exercise is to show the failure first, with evidence, and only then fix it.

create trigger venue_set_updated_at before update on venue
    for each row execute function set_updated_at();
create trigger seat_section_set_updated_at before update on seat_section
    for each row execute function set_updated_at();
create trigger seat_set_updated_at before update on seat
    for each row execute function set_updated_at();
create trigger event_set_updated_at before update on event
    for each row execute function set_updated_at();
create trigger event_seat_set_updated_at before update on event_seat
    for each row execute function set_updated_at();
create trigger booking_set_updated_at before update on booking
    for each row execute function set_updated_at();
create trigger booking_seat_set_updated_at before update on booking_seat
    for each row execute function set_updated_at();
