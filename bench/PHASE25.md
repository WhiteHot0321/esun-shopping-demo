# Phase 2.5 acceptance

## Acceptance scope and result authority — 2026-09-16

See [Task 007](../docs/tasks/007-phase15-baseline-acceptance.md) for the separated
requirements and evidence ledger. B0 = Redis off / attempts=1, C3 = Redis off /
attempts=3, R3 = Redis on / attempts=3, all on one frozen current-code snapshot.
Phase 1.5's 40-VU/45-second historical and sustained-load scenarios are separate
from this runner's 20-VU/20-second comparison. Do not substitute one for another.
Record each configuration for three identical fresh-data runs; retain every run,
including null results. A successful HTTP replay is not a newly created order.

Latest runtime result is Task 006 (2026-09-16): independent repair review static
PASS, focused invocation exit 1 with 2 passed and 13 Docker initialization errors.
Current complete fault/load/coverage acceptance is still pending. This document
update does not probe Docker or run tests. Runtime logs and outputs under target/
must be preserved outside the clean-build output before running mvn clean test.

Run from `backend/` with Docker and k6 available. These runners use disposable
Testcontainers databases/cache, not the user's Compose volumes.

```powershell
mvn -q "-Dtest=RealDeadlockRetryIntegrationTest" "-Dorder.retry.max-attempts=1" test
mvn -q "-Dtest=RealDeadlockRetryIntegrationTest" "-Dorder.retry.max-attempts=3" test
mvn -q "-Dtest=Phase25K6Acceptance" "-Dstock.redis.enabled=false" "-Dorder.retry.max-attempts=1" test
mvn -q "-Dtest=Phase25K6Acceptance" "-Dstock.redis.enabled=false" "-Dorder.retry.max-attempts=3" test
mvn -q "-Dtest=Phase25K6Acceptance" "-Dstock.redis.enabled=true" "-Dorder.retry.max-attempts=3" test
```

The deterministic failure runner coordinates 20 real InnoDB lock cycles; Mockito
only places timing barriers around the real repository call. It verifies MySQL
1213 on the negative control, rollback, exact inventory and actual retry count.
The ordinary k6 runner uses the existing three-item permutation script, 20 VUs,
20 seconds, JWT registration, 10,000 initial units per product and assertions for
success >=95%, exact DB order/stock deltas and Redis drift. Its summary/log files
are under `backend/target/phase25-k6-*`. Seed orders are excluded by taking a
before/after count. It is invoked explicitly and is not part of the default suite.

Do not equate a controlled deadlock failure rate with an ordinary workload's
failure rate, or assume the historical ~50% result reproduces on the newer FK
lock-order implementation. Report both measurements, including a null result.
Narrow `mvn test` selections may fail the restored >=80% coverage check even when
their JUnit tests pass; only the final clean full suite establishes that gate.

## Cache operations

- `stock.redis.enabled` defaults false. Redis connect/command timeouts are 500 ms.
- DB request ownership is claimed before Redis reservation; cache reservation
  precedes DB inventory writes. Duplicate callers wait for the claim transaction
  and return its committed order without taking another reservation.
- A confirmed reservation is compensated atomically after the DB proxy rolls
  back. An unavailable/missing/ambiguous Redis result latches this process into
  DB-only mode; it does not manufacture compensation for an unconfirmed decrease.
- The scheduler logs `Stock audit` every 60 seconds, comparing **all** DB products,
  including zero stock, with Redis. During active writes it may see outstanding
  reservations; verify final drift with writes stopped and in-flight work drained.
- Startup preload uses SET NX, never overwriting live reservations. It is **not**
  reconciliation after an outage, DB-only operation or a cache restart. Do not
  simply restart to clear degraded mode and trust stale cache values.
- Recovery procedure: pause **all** order writers, drain in-flight transactions,
  take the authoritative product stock snapshot from MySQL, replace only this
  application's `stock:{productId}` values with that snapshot (including zeros),
  verify all keys against DB, then restart writers and inspect the audit log.
  Do not FLUSHALL a shared Redis. Keep the feature disabled until this maintenance
  is completed. Automatic cross-instance reconciliation is not provided.
- DB conditional stock updates remain the final no-oversell guarantee. Cache
  network ambiguity may cause drift; this is not a distributed transaction.

## Results

2026-09-15 controlled deadlocks: attempts=1 -> 0/20 success, MySQL 1213 for all;
attempts=3 -> 20/20 success, 20 actual retries. Both test assertions passed.
Task 006 records historical 2026-09-15 ordinary k6: B0 836/836, C3 849/849,
R3 812/812; controlled HTTP attempts=1 had 821 successes and 10 conflicts.
These are prior artifacts, not current full acceptance or evidence of improvement
over an assumed 50% baseline. Current paired controlled HTTP attempts=1/3,
repeated ordinary comparisons, failure scenarios and final clean coverage remain
pending; do not interpret this section as a live running process.
