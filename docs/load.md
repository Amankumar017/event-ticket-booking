# Two hundred customers at once

```bash
cd backend
./mvnw test -Dtest=SeatRushLoadTest -DexcludedTestGroups=none
```

Two hundred accounts, two hundred virtual threads, all released by one barrier
against a throwaway PostgreSQL container. Two scenarios: everybody wants a
different seat, and everybody wants the same one.

## What the numbers are, and what they are not

**The measurements below describe this laptop.** Across three runs the
sell-out scenario varied by more than a factor of two, from 67 to 172 holds a
second, because two hundred simultaneous commits on a developer machine compete
with everything else running on it. Quoting a single number would be
inventing a precision that is not there.

**The assertions are the point.** They held identically on every run:

- every hold either succeeded or was honestly refused: **zero errors**
- **zero seats claimed twice**, checked by the audit query
- when two hundred people wanted one seat, **exactly one got it**

A slower run is information. A run that oversells a seat is a bug, and no run
has ever done it.

## Everybody wants a different seat

Two hundred customers, two hundred seats. Nothing contends, so this measures how
fast the whole path runs: guard, lock, check, insert, commit, announce.

| Run | Wall clock | Throughput | Granted | Errors | p50 | p95 | p99 | Claimed twice |
| --- | ---------- | ---------- | ------- | ------ | --- | --- | --- | ------------- |
| 1   | 1164 ms    | 172/s      | 200     | 0      | 702 ms | 1101 ms | 1136 ms | 0 |
| 2   | 2991 ms    | 67/s       | 200     | 0      | 1749 ms | 2813 ms | 2938 ms | 0 |
| 3   | 1750 ms    | 114/s      | 200     | 0      | 999 ms | 1678 ms | 1713 ms | 0 |

The latencies look alarming until you notice the connection pool is **ten**.
Two hundred callers arriving together means a hundred and ninety of them are
queueing for a connection before they do anything at all, and each one's
measured latency includes that wait. This is a picture of a deliberately small
pool under a deliberately unreasonable spike, not of a slow booking path, and
`hikaricp_connections_pending` on the Grafana dashboard shows exactly that
queue.

## Everybody wants the same seat

The scenario the whole project exists for. Stage 5 measured eight contenders;
this is two hundred.

| Run | Wall clock | Granted | Refused | Errors | p50 | p95 | p99 | Claimed twice |
| --- | ---------- | ------- | ------- | ------ | --- | --- | --- | ------------- |
| 1   | 290 ms     | 1       | 199     | 0      | 166 ms | 272 ms | 281 ms | 0 |
| 2   | 273 ms     | 1       | 199     | 0      | 140 ms | 258 ms | 258 ms | 0 |
| 3   | 131 ms     | 1       | 199     | 0      | 72 ms  | 122 ms | 127 ms | 0 |

Faster than the sell-out scenario, and consistently so, which is worth a moment:
a hundred and ninety-nine of these requests are turned away by the Redis guard
before they ever open a transaction. Rejecting early is cheaper than queueing,
which is the entire argument for having the guard, and the reason it is allowed
to say "no" but never "yes".

Compare with the unlocked implementation measured in
[concurrency.md](concurrency.md), which at eight contenders either deadlocked
seven of them or sold one chair to all eight.

## Watching it happen

```bash
docker compose up -d          # brings up Prometheus and Grafana too
```

Grafana is at <http://localhost:3001> with the dashboard already provisioned;
Prometheus is at <http://localhost:9091>. The panels worth watching during a
run are hold latency, the connection pool's pending count, and the guard's
allowed-against-rejected split.
