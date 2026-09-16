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
over an assumed 50% baseline.

### Current-version run — 2026-09-16 09:14-09:22 Asia/Taipei, one round each

Docker Desktop recovered (28.4.0); this is the first current-code run since the
prior session's environment blocker. Baseline: advanced-v2 @ 5574f05 plus the
existing uncommitted Phase 2.5 working tree, unchanged. One round per
configuration was executed (not the three rounds Task 007 specifies for a full
statistical acceptance); treat this as a passing functional/regression run, not
the complete multi-round P25 acceptance ledger.

- `RealDeadlockRetryIntegrationTest` attempts=1 (negative control): 20/20 real
  InnoDB deadlocks, all rejected with MySQL error code 1213, 0 retries,
  0 successes, orphaned `order_request` rows = 0 for every rejected caller.
  Exit code 0.
- `RealDeadlockRetryIntegrationTest` attempts=3: 20/20 succeeded after exactly
  one retry each (20 actual retries), post-run stock exactly matches the
  expected `quantity=9` per contested pair (from seeded 10). Exit code 0.
- `Phase25K6Acceptance` B0 (`stock.redis.enabled=false`, attempts=1): total=910,
  success=910 (100%), retries=0, ending stock=9090 (matches
  10000-success by DB delta), audit=DB-only (Redis disabled, no drift check
  applicable). Exit code 0.
- `Phase25K6Acceptance` C3 (`stock.redis.enabled=false`, attempts=3): total=895,
  success=895 (100%), retries=0 (20 VUs/20s at 10,000 initial units never
  contends enough to deadlock), ending stock=9105. Exit code 0.
- `Phase25K6Acceptance` R3 (`stock.redis.enabled=true`, attempts=3): total=871,
  success=871 (100%), retries=0, ending stock=9129, `cache.audit()` returned
  empty (no Redis/DB drift). Exit code 0.
- All three ordinary-load runs stayed far above the required >=95% success rate
  because normal-load stock (10,000 units, 20 VUs, 20s) never approaches
  exhaustion; this run does not exercise the sold-out/409 path bucketed by
  Task 007 section 4 and is not a substitute for a stress run that depletes
  inventory.
- Redis fault suite `OrderRetryTest,StockCacheServiceIntegrationTest,
  RedisOrderIntegrationTest,RedisUnavailableOrderIntegrationTest`: all passed
  (Maven exit 0); includes the intentional DB-unavailable-during-audit case
  (`StockCacheServiceIntegrationTest.auditSchedulerHandlesUnavailableDatabaseWithoutThrowing`)
  logging a caught `IllegalStateException` without failing the test or throwing
  from the scheduled method.
- Final `mvn clean test` (full suite, fresh JaCoCo run): 72/72 tests passed,
  0 failures/errors/skipped, BUILD SUCCESS. Gated-class line coverage from
  `target/site/jacoco/com.esun.shop.service/index.html`: OrderService 34/36
  lines = 94.4%, OrderTransactionService 52/52 = 100%, StockCacheService
  60/63 = 95.2%, ProductService 18/18 = 100%. All four clear the restored 80%
  line-coverage gate; the JaCoCo `check` goal did not fail the build.

Not covered by this run: three-round repetition per configuration, explicit
MySQL 1213/1205 bucketed counts under ordinary (non-controlled) load, a
stress/sold-out scenario, and the Redis outage/recovery/reconciliation
procedure in "Cache operations" above. Current paired controlled HTTP
attempts=1/3 (`Phase25K6DeadlockAcceptance`) was not run this session.

### Three-round repeat — 2026-09-16 10:31-10:40 Asia/Taipei (Task 010A)

Closes the "three-round repetition" gap noted above. **Two independent Claude Code sessions
ran Task 010A concurrently on the same checkout without coordinating** (an instance of
AGENTS.md's "one writer per checkout" being violated) and each produced its own full batch of
9 rounds; both are real, neither replaces the other. Full per-round tables for both batches and
the reconciliation note are in
[docs/tasks/011-phase25-3round-result.md](../docs/tasks/011-phase25-3round-result.md); summary:

| Config | Batch 1 success (median/range) | Batch 1 total reqs (median/range) | Batch 2 success (median/range) | Batch 2 total reqs (median/range) |
|---|---|---|---|---|
| B0 (Redis off, attempts=1) | 100% / 100%-100% | 705 / 702-734 | 100% / 100%-100% | 783 / 687-785 |
| C3 (Redis off, attempts=3) | 100% / 100%-100% | 729 / 728-756 | 100% / 100%-100% | 732 / 687-798 |
| R3 (Redis on, attempts=3) | 100% / 100%-100% | 737 / 704-874 | 100% / 100%-100% | 714 / 678-748 |

All 18 rounds across both batches: Maven exit 0, ending stock reconciled exactly to
`10000 - success`, R3's `cache.audit()` empty in every R3 round. Zero retries in every C3/R3
round in both batches confirms this load shape (20 VUs/20s, 10,000 initial units) never
approaches enough contention to exercise the retry path; still not a substitute for the
stock-depleting stress scenario or the controlled deadlock runners. Still not covered: the
Redis outage/recovery drill (Task 010 sub-task B, separate session) and
`Phase25K6DeadlockAcceptance`'s paired HTTP attempts=1/3 comparison.
