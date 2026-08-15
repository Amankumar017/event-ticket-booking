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

Requires Docker and a JDK 21 or newer. Maven is supplied by the wrapper.

```bash
docker compose up -d          # Postgres on 5433, Redis on 6380
cd backend
./mvnw spring-boot:run        # http://localhost:8090
```

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
| Application | 8090      |
| PostgreSQL  | 5433      |
| Redis       | 6380      |

## Status

Under construction. The schema is owned by Flyway from the first migration
onward; domain tables land next.
