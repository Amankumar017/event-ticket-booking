-- Accounts, and the refresh tokens that keep them signed in.

-- "user" is reserved in PostgreSQL, hence the prefix.
create table app_user (
    id            bigint generated always as identity primary key,
    email         varchar(160) not null,
    password_hash varchar(100) not null,
    display_name  varchar(120) not null,
    role          varchar(20)  not null default 'CUSTOMER',
    enabled       boolean      not null default true,
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now(),
    constraint app_user_role_is_known check (role in ('CUSTOMER', 'ORGANIZER', 'ADMIN'))
);

-- Case-insensitive uniqueness. Storing the address as typed keeps it readable in
-- support tickets; the index is what stops Aman@example.com and aman@example.com
-- becoming two accounts.
create unique index app_user_email_unique on app_user (lower(email));

-- Refresh tokens are stored as SHA-256 hashes, never in the clear.
--
-- The value only ever exists in the cookie held by the browser it was issued to.
-- Anyone reading this table -- a leaked backup, an over-broad support query, a
-- SQL injection that gets as far as a SELECT -- comes away with nothing they can
-- present as a token.
create table refresh_token (
    id          bigint generated always as identity primary key,
    user_id     bigint       not null references app_user (id) on delete cascade,
    token_hash  varchar(64)  not null unique,
    -- Every token descended from one login shares a family id. Rotation issues a
    -- new token in the same family, so a stolen token can be traced back to the
    -- session it came from and the whole family revoked at once.
    family_id   uuid         not null,
    issued_at   timestamptz  not null default now(),
    expires_at  timestamptz  not null,
    revoked_at  timestamptz,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now()
);

create index refresh_token_by_user on refresh_token (user_id);
create index refresh_token_by_family on refresh_token (family_id);

create trigger app_user_set_updated_at before update on app_user
    for each row execute function set_updated_at();
create trigger refresh_token_set_updated_at before update on refresh_token
    for each row execute function set_updated_at();

-- A booking now belongs to an account rather than to whatever name the request
-- happened to carry. Nullable: bookings made before accounts existed keep their
-- customer_name and customer_email and have no owner.
alter table booking
    add column user_id bigint references app_user (id);

create index booking_by_user on booking (user_id);
