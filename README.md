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

## Status

Under construction. The schema is owned by Flyway from the first migration
onward; domain tables land next.
