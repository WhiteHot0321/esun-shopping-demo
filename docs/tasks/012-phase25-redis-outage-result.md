# Task 012 — Phase 2.5 Task 010B: Redis outage/recovery drill result

## Task

Execute [Task 010](010-phase25-followup-3round-outage.md) sub-task B: get first-hand evidence
of the Redis outage/recovery procedure described in `bench/PHASE25.md`'s "Cache operations"
section — that an unreachable Redis latches `StockCacheService` into DB-only mode, that the
latch does not auto-clear, and that the documented recovery procedure (DB-authoritative
snapshot rewritten into Redis) converges `cache.audit()` back to empty — rather than inferring
it only from existing unit/integration tests.

## Problem

`bench/PHASE25.md`'s Cache operations section made several specific behavioral claims
(one-way `degraded` latch, DB as final authority, recovery-by-snapshot converges the audit)
that were backed by reading `StockCacheService.java` and by tests that simulate an unreachable
Redis for the *order flow specifically* (`RedisUnavailableOrderIntegrationTest`), but nothing
exercised the *outage → persistence of degraded → manual recovery → still-degraded → actual
restart* sequence end-to-end as its own scenario.

## Root Cause

Not applicable in the usual defect sense — like Task 010A, this was a deliberately deferred
scope item (Task 010 explicitly split sub-task B into its own session), not a bug.

## Solution

`backend/src/test/java/com/esun/shop/service/RedisOutageRecoveryDrillTest.java` was authored
(by a concurrently-running session on this same checkout — see "Collision" below) and, after
one correctness fix applied in this session, passes. Design:

- One disposable Testcontainers Redis, started once and never manipulated (no pause/stop/kill
  of the container itself) — see "Collision" for why.
- "Outage" is simulated the same way the existing `RedisUnavailableOrderIntegrationTest`
  already does for the full order flow: a second, independent Lettuce client pointed at
  `127.0.0.1:1` (nothing listens there), producing a real `RedisConnectionException` that
  `StockCacheService.tryDecrease` catches and latches `degraded=true` on.
- DB truth is tracked as a plain `Map` behind a mocked `ProductRepository`, since the
  DB-conditional-decrement-is-final-authority guarantee is already covered end-to-end by other
  integration tests; this drill's job is specifically the cache/reconciliation contract.
- Sequence exercised: healthy baseline (Redis reservation + DB move in lockstep) → outage
  (first call returns `BYPASSED`, DB is simulated as still moving) → second call on the *same*
  degraded instance, after its connection factory is destroyed outright, still returns
  `BYPASSED` cleanly (no throw, no hang) → `cache.audit()` shows drift for the product that was
  touched during the outage, and no drift for one that wasn't → recovery (DB snapshot rewritten
  into every `stock:{productId}` key, including ones that never drifted) → `audit()` empty →
  the *same* (still-degraded) instance is shown NOT to resume Redis just because the keys were
  fixed → a fresh `StockCacheService` instance (simulating an actual process restart, sharing
  the same healthy Redis client) resumes real Redis reservations and stays in sync.

### Collision and correctness fix (this session's actual contribution)

A different Claude Code session was independently implementing this exact sub-task on this same
checkout at the same time, without coordination (the same kind of collision recorded for Task
010A and the Phase 2 merge). Two versions were observed and torn down/rewritten live during this
session:

1. An `integration` package version using `DockerClient.stopContainerCmd`/`startContainerCmd`
   on the real Testcontainers Redis container. **Ran and failed**: `docker start` after
   `docker stop` reassigns a new random host port on this Windows/Docker Desktop host (verified
   independently with a throwaway `docker run -p 0:6379` / `stop` / `start` / `docker port`
   round trip: the mapped port changed, e.g. 32878 -> 32879). Spring's `@DynamicPropertySource`
   resolves the mapped port once before context startup, so the app kept dialing the now-dead
   original port forever, and `waitForRedis()` failed after 30s. This is a host/Docker-Desktop
   quirk, not a `StockCacheService` defect — but it makes `stop`/`start` unusable here.
2. A rewritten `service` package version (superseding #1) that sidesteps container manipulation
   entirely via the second-broken-client approach described above. **Ran and failed once more**:
   it asserted the first outage call took >=400ms ("paid the real 500ms command timeout"), but
   connecting to a locally *refused* port (`127.0.0.1:1`) fails near-instantly (35ms observed) —
   the OS returns ECONNREFUSED immediately; a slow *timeout* only happens when packets are
   silently dropped (e.g. a black-holed route), not when a port is actively refused. This
   assertion was corrected (by the other session, live, while this session was mid-diagnosis of
   the same bug) to prove the same claim — that the second call short-circuits before touching
   Redis again — by destroying the broken connection factory outright before the second call,
   instead of relying on wall-clock timing at all. This session independently reproduced the
   35ms failure via `mvn -Dtest=RedisOutageRecoveryDrillTest test`, confirming the bug was real
   before the fix landed, then re-ran the corrected version to confirm it passes.

Neither this session nor the other overwrote the other's real work here — both diagnosed the
same underlying issues (in different components: the Docker port-reassignment bug and the
refused-vs-timeout assertion bug) essentially independently, and the final file is the union of
both fixes.

## Engineering Concept

**Two failure-mode classes for a "network dependency" test double, and why they matter**:
a refused port (ECONNREFUSED, fast) and a black-holed/hung route (silent timeout, slow) are both
legitimate ways a real dependency can become "unavailable," but they are not interchangeable in
tests that assert *how* the failure manifests, only in tests that assert *what the caller does
in response*. `StockCacheService.tryDecrease`'s catch block treats both identically (any
`RuntimeException` -> latch `degraded`), which is correct and is why the app-level test doesn't
need to care which one it hits — but a test that additionally asserts elapsed time is silently
asserting a specific failure *mode*, not just the outcome, and breaks the moment the simulated
failure's mode doesn't match that assumption. The fix generalizes past both failure modes by
asserting the same observable fact (short-circuit before touching Redis) through a mechanism
that doesn't depend on which mode occurred: destroying the connection outright removes any path
to a real attempt, succeed or fail, fast or slow.

## Test Result

- `mvn -Dtest=RedisOutageRecoveryDrillTest test`: 1/1 passed (0 failures, 0 errors) after the fix.
- `mvn clean test` (full suite, this session, post-fix): 75 tests, 0 failures/errors/skipped
  (74 prior + this new test), JaCoCo `check-core-services` gate passed, Maven exit 0.
- Outage-period behavior confirmed: `BYPASSED` returned twice (first via a real
  `RedisConnectionException`, second via a destroyed connection factory) with no throw and no
  hang either time; DB truth kept moving underneath (no oversell/no data-loss guarantee is the
  DB-conditional decrement, exercised elsewhere, not re-proven here).
- Post-outage-but-pre-recovery: `cache.audit()` correctly reported drift only for the product
  touched during the outage, not for an untouched one.
- Post-recovery (DB snapshot rewritten into Redis): `cache.audit()` empty.
- Still-degraded-after-recovery: the same instance stayed on `BYPASSED` even after the Redis
  values were fixed, confirming the recovery procedure's "restart" step is a real process
  restart, not merely resuming traffic or fixing data.
- Simulated restart (fresh `StockCacheService` instance, same healthy Redis client): resumed
  real `RESERVED` reservations and stayed convergent (`audit()` empty).

## Trade-offs

- This drill exercises `StockCacheService` directly with a mocked `ProductRepository`, not the
  full HTTP → `JwtAuthFilter` → `OrderController` → `OrderService` → `OrderTransactionService`
  path under an actual outage. That full-path DB-final-authority guarantee is covered by other
  existing integration tests (`RedisUnavailableOrderIntegrationTest`,
  `RedisOrderIntegrationTest`), so this isn't a coverage gap, but it means this specific drill's
  evidence is scoped to the cache/reconciliation contract, not a re-proof of order placement
  under outage.
- No cross-instance reconciliation scenario was exercised (out of scope per Task 010, matching
  `bench/PHASE25.md`'s explicit "Automatic cross-instance reconciliation is not provided" note).
- The disposable Redis container itself is never manipulated in this final design, so this drill
  does not independently re-verify that a *real* Testcontainers-level outage (as opposed to a
  second broken client) produces the identical behavior; that was the point of the two now-
  abandoned earlier attempts, both of which hit environment-specific tooling problems rather
  than app-logic problems before being superseded.

## Next Step

- Task 010 (both sub-tasks A and B) is now complete. `Phase25K6DeadlockAcceptance`'s paired HTTP
  attempts=1/3 controlled-deadlock comparison remains an independent, unscoped gap, as noted in
  Task 010 itself.
- Given two independent concurrent-session collisions happened in this one Phase 2.5 follow-up
  task alone (Task 010A's result-doc clobber, and this test file's live rewrite), revisiting
  whether "one writer per checkout" needs an actual enforcement mechanism (a lock file, a
  claimed-task marker in docs/tasks/, or literally separate worktrees for concurrent sessions as
  AGENTS.md already prescribes for parallel implementation) is worth raising with the user,
  rather than continuing to rely on each session noticing and reconciling after the fact.
