-- Payments, and the three tables that make "exactly once" achievable across a
-- network that only offers "at least once".

create table payment (
    id                 bigint generated always as identity primary key,
    booking_id         bigint       not null references booking (id) on delete cascade,
    -- What the provider calls this payment. Unique because the provider will
    -- quote it back to us in every webhook about it.
    provider_reference varchar(64)  not null unique,
    amount_minor       bigint       not null,
    currency           varchar(3)   not null default 'INR',
    status             varchar(20)  not null default 'REQUIRES_PAYMENT',
    failure_reason     varchar(200),
    settled_at         timestamptz,
    created_at         timestamptz  not null default now(),
    updated_at         timestamptz  not null default now(),
    constraint payment_status_is_known
        check (status in ('REQUIRES_PAYMENT', 'SUCCEEDED', 'FAILED')),
    constraint payment_amount_is_positive check (amount_minor > 0)
);

-- At most one payment still awaiting settlement per booking. Failed attempts
-- stay for the audit trail and do not block another try.
create unique index payment_one_open_attempt_per_booking
    on payment (booking_id)
    where status = 'REQUIRES_PAYMENT';

create index payment_by_booking on payment (booking_id);

-- Replies kept so a retried request can be answered without doing the work
-- twice.
--
-- request_fingerprint is a hash of the body the key was first used with. A key
-- reused with different content is a client bug, and answering it with the old
-- reply would be worse than refusing it.
create table idempotency_key (
    id                  bigint generated always as identity primary key,
    idempotency_key     varchar(200) not null,
    user_id             bigint       not null references app_user (id) on delete cascade,
    request_fingerprint varchar(64)  not null,
    state               varchar(20)  not null default 'IN_PROGRESS',
    response_status     integer,
    response_body       text,
    created_at          timestamptz  not null default now(),
    updated_at          timestamptz  not null default now(),
    constraint idempotency_state_is_known check (state in ('IN_PROGRESS', 'COMPLETED')),
    -- Scoped per account: two customers using the same key is a coincidence,
    -- not a repeat, and neither should see the other's reply.
    constraint idempotency_key_unique_per_user unique (user_id, idempotency_key)
);

create index idempotency_key_by_age on idempotency_key (created_at);

-- Every webhook the provider has delivered, by its own event id.
--
-- Providers deliver at least once: a network hiccup on the way back means the
-- same event arrives again. The unique constraint is what turns that into a
-- no-op instead of a second confirmation.
create table webhook_event (
    id                bigint generated always as identity primary key,
    provider_event_id varchar(100) not null unique,
    event_type        varchar(60)  not null,
    payload           text         not null,
    received_at       timestamptz  not null default now(),
    processed_at      timestamptz,
    created_at        timestamptz  not null default now(),
    updated_at        timestamptz  not null default now()
);

-- Messages to send once the transaction that caused them has committed.
--
-- Sending an email inside the transaction would send it for work that then
-- rolled back; sending it after commit loses it if the process dies in between.
-- Writing the intent to the same transaction, and letting a separate job do the
-- sending, is the only arrangement where the message and the fact it describes
-- cannot disagree.
create table outbox_message (
    id           bigint generated always as identity primary key,
    message_type varchar(60)  not null,
    recipient    varchar(160) not null,
    payload      text         not null,
    attempts     integer      not null default 0,
    sent_at      timestamptz,
    last_error   varchar(300),
    created_at   timestamptz  not null default now(),
    updated_at   timestamptz  not null default now()
);

create index outbox_unsent on outbox_message (created_at) where sent_at is null;

create trigger payment_set_updated_at before update on payment
    for each row execute function set_updated_at();
create trigger idempotency_key_set_updated_at before update on idempotency_key
    for each row execute function set_updated_at();
create trigger webhook_event_set_updated_at before update on webhook_event
    for each row execute function set_updated_at();
create trigger outbox_message_set_updated_at before update on outbox_message
    for each row execute function set_updated_at();
