# Seatly

Event ticket booking with seat-level concurrency control.

Selling a numbered seat is one of the few genuinely hard problems hiding inside
an ordinary CRUD application: the moment two people click the same seat in the
same instant, correctness depends entirely on how the write is serialised.
Seatly exists to work that problem end to end — a naive implementation that
demonstrably double-books, the measurements that expose it, and the locking
strategy that fixes it.

## Stack

| Layer    | Choice                                              |
| -------- | --------------------------------------------------- |
| Backend  | Java 21, Spring Boot 4, Spring Data JPA             |
| Database | PostgreSQL 17, schema migrated by Flyway            |
| Cache    | Redis 8 (seat holds with TTL)                       |
| Frontend | Angular 20 (standalone components, signals)         |
| Testing  | JUnit 5, Testcontainers                             |

## Running locally

Requires Docker, a JDK 21 or newer, and Node 20.19+ for the frontend. Maven is
supplied by the wrapper.

```bash
docker compose up -d                                    # Postgres 5433, Redis 6380
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=seed   # http://localhost:8090
```

The `seed` profile puts one venue and one on-sale event into the database, and
does nothing if they are already there.

```bash
cd frontend
npm start                     # http://localhost:4200
```

The dev server proxies `/api` to port 8090, so the browser sees a single origin.
CORS is configured separately for deployments where the two are served apart.

Health, including database and Redis connectivity:

```bash
curl http://localhost:8090/actuator/health
```

Tests boot the application against throwaway containers, so Docker must be
running but the compose stack need not be:

```bash
cd backend
./mvnw test
```

## Ports

Non-default host ports are used throughout so the stack can run alongside other
local services.

| Service     | Host port |
| ----------- | --------- |
| Angular      | 4200      |
| Application | 8090      |
| PostgreSQL  | 5433      |
| Redis       | 6380      |

## Accounts

```
POST   /api/auth/register                       create an account and sign in
POST   /api/auth/login                          sign in
POST   /api/auth/refresh                        renew, using the refresh cookie
POST   /api/auth/logout                         end this session
GET    /api/auth/me                             who am I
```

The access token is short-lived, signed, and returned in the response body for
the client to hold in memory. The refresh token never appears in a body at all:
it lives in an http-only, `SameSite=Strict` cookie scoped to `/api/auth`, so no
script on the page can read it and no other site can cause it to be sent.

Refresh tokens are **rotated** — each one may be spent exactly once, and using it
issues a successor. A token that has already been spent turning up again means
two parties hold it, so the whole session family is revoked and both are made to
sign in. Stored as SHA-256 hashes, never in the clear.

Browsing is open to anyone. Buying requires an account, and identity comes from
the verified token rather than from anything in the request body.

Seeded accounts under the `seed` profile, both with password `seatly-demo-pass`:
`customer@example.com` and `organiser@example.com`.

## Booking a seat

```
POST   /api/bookings                            hold seats for five minutes
POST   /api/bookings/{reference}/confirmation   turn a live hold into a sale
POST   /api/bookings/{reference}/cancellation   give the seats back early
GET    /api/bookings/{reference}                look one up
GET    /api/events                              what is on sale
GET    /api/events/{id}/seats                   the seating chart
```

A hold is a PENDING booking with a deadline. Confirm before it lapses and the
seats are sold; miss it and they go back on sale — immediately, because
availability is decided from the deadline itself rather than from a background
job having caught up. The job exists to make the stored state agree.

Failures come back as RFC 9457 problem documents, so `Seat A1 is no longer
available` arrives as text a client can show rather than a status code it has to
interpret.

## Status

Under construction. Seats can be browsed, held, confirmed and cancelled; holds
expire on their own; and customers sign in with rotating refresh tokens. Payment
and live seat updates are still to come.

The concurrency work — what the unlocked version did under load, and what fixed
it — is written up with its measurements in [docs/concurrency.md](docs/concurrency.md).
