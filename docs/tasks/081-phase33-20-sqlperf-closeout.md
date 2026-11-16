# 081 — Phase 3.3 #20 SQL performance: local closeout (cache re-measure, ordering pool/lock, cache review)

Date: 2026-11-16 (Asia/Taipei). Executor: Claude Code (implementation-engineer). Branch: `feature/phase33-20-sqlperf`
(cut from `advanced-v2` at `cd04e78`). Parents: `062` (baseline), `071` (recommendation cache).
Scope: only the parts of #20 that need no external resource. Everything ran on one Windows 11 host (16 logical CPUs,
Docker Desktop) against throw-away `mysql:8.0` containers. No dev (`esun-mysql`) or production data was touched; no secrets.

## What changed

| File | Change |
|---|---|
| `bench/sqlperf/app_load.py` (new) | Repeatable application-level bench: disposable MySQL (published on 127.0.0.1 only, per-run random root password, all Flyway `V*.sql` + `seed.sql`), runs the backend jar twice (cache TTL 0 and 30 s), measures the public recommendation endpoint and concurrent ordering. Actuator is served on a loopback management port (as in prod) because the API port puts it behind the JWT filter. |
| `bench/sqlperf/results/app-load-run1.json`, `app-load-run2.json` (new) | Raw results of the two runs below. |
| `backend/src/test/java/com/esun/shop/service/PublicRecommendationCacheTest.java` | +4 tests from the review (cap, failed refresh, negative TTL, immutable snapshot). 6 → 10 cases. |

No production code changed. No instrumentation was added to the app; Hikari numbers come from the existing Micrometer
`hikaricp.*` meters, lock/transaction numbers from MySQL status counters and `performance_schema`.

Reproduce: `mvn -q package -DskipTests` in `backend/`, then
`python bench/sqlperf/app_load.py --jar backend/target/shopping-backend-1.0.0.jar --out bench/sqlperf/results/app-load-runN.json`
(about 4 minutes; container and app are removed at the end, backend logs go to the temp directory).

Dataset: the 062 `seed.sql` (5k members, 2k products, 100k orders, ~250k order lines), MySQL 8.0.46, 128 MB buffer pool,
REPEATABLE-READ, seed 25 s.

## (1) Public recommendations with the 30 s single-flight cache

`GET /api/products/{id}/recommendations?limit=6`. "DB stmt" = server-side statement time from
`events_statements_summary_by_digest` (truncated before each phase); "mysqld CPU" = container cgroup `usage_usec` delta
(includes MySQL background threads, so an upper bound). `SUM_CPU_TIME` is not collected on a default 8.0 server, so
per-statement CPU is not available; the cgroup number is used instead. Both are divided by HTTP calls.

| Scenario | Run | TTL 0 (cache off) | TTL 30 s (cache on) |
|---|---|---|---|
| 20 sequential calls, hot product `P00001` — HTTP latency | 1 | first 598 ms, rest median 854 / p95 1403 ms | first (miss) 575 ms, rest (hits) median **14.3** / p95 16.6 ms |
|  | 2 | first 462 ms, rest median 425 / p95 455 ms | first (miss) 457 ms, rest (hits) median **15.7** / p95 27.0 ms |
| same — recommendation aggregates executed / 20 calls | 1, 2 | 20, 20 | **1, 1** |
| same — DB stmt ms per call | 1 / 2 | 899 / 401 | 28.3 / 21.6 |
| same — mysqld CPU ms per call | 2 | 414 | 24.2 (the one miss amortised over 20) |
| 32 concurrent callers, one cold key `P01201` — latency | 1 | median 1919 / max 3238 ms | median 323 / max 325 ms |
|  | 2 | median 1456 / max 2567 ms | median 354 / max 355 ms |
| same — aggregates executed / 32 calls | 1, 2 | 64, 64 (2 per call: co-purchase + popular filler) | **2, 2** (one load) |
| same — mysqld CPU ms total | 2 | 21,854 | 384 |
| 50 distinct keys, all misses — latency | 2 | median 31.0 / p95 361 ms | median 30.6 / p95 352 ms |
| same — mysqld CPU ms per call | 2 | 46.8 | 43.7 |

Reading:
- A hit costs no recommendation SQL; the HTTP latency of a hit (~15 ms on this host, client and server on one machine,
  Python `urllib`) is serialisation and transport.
- Single-flight is confirmed end to end: 32 simultaneous callers on a cold key made exactly one load (2 aggregate
  statements), all 32 returned 200 in ~0.32-0.35 s, versus 64 aggregates and ~22 s of mysqld CPU uncached.
- Misses cost the same with and without the cache (the 50-distinct-key rows agree within noise), so the cache does not
  help a caller who walks many products; per-miss cost depends on the anchor (hot product ~0.4-0.9 s, typical ~30 ms
  median). Abuse by key walking is bounded only by the 2000-entry cap and the product count — rate limiting (071) is
  still the open complement.
- Run-to-run spread is large for the uncached hot key (median 854 vs 425 ms): consistent with the 062 plan-flip
  observation for `reco.co_purchase`; I did not capture plans in this run.
- Unattributed: on the TTL-30 sequential phase the 19 hits still showed ~23 small statements in total (< 4 ms DB time
  overall); the burst phase showed none beyond the single load. Probably Hikari opening its remaining idle connections
  after start-up; not verified.

## (2) Concurrent ordering — Hikari pool and lock duration

200 two-line orders (`P00001` + `P00002`, request order alternated so the service's `productId` lock ordering is
exercised), 20 throw-away buyers registered through the public API, after a discarded 20-order warm-up. Hikari pool
max = 10 (default). Hikari pending/active were sampled every ~20 ms from `/actuator/metrics`; acquire/usage are timer
deltas; `*_window` maxima are Micrometer's decaying max.

| Metric | c=8, run 1 | c=8, run 2 | c=32, run 1 | c=32, run 2 |
|---|---:|---:|---:|---:|
| HTTP 200 / 200 orders | 200 | 200 | 200 | 200 |
| Throughput (orders/s) | 38.2 | 41.7 | 37.7 | 44.6 |
| Client latency median / p95 (ms) | 207 / 262 | 191 / 218 | 810 / 1145 | 704 / 757 |
| Hikari active peak (sampled) | 8 | 8 | 10 | 10 |
| Hikari pending peak (sampled) | 0 | 0 | 22 | 21 |
| Hikari acquire wait mean / window max (ms) | 0.015 / 1.2 | 0.012 / 1.2 | **261 / 802** | **216 / 493** |
| Hikari connection usage (held) mean / window max (ms) | 95 / 570 | 85 / 438 | 129 / 624 | 109 / 438 |
| Connection checkouts | 400 | 400 | 400 | 400 |
| Read-write transactions: count / avg / max (ms) | 400 / 94.6 / 272 | 400 / 84.7 / 224 | 400 / 128.5 / 622 | 400 / 108.1 / 289 |
| InnoDB row-lock waits / total wait (ms) | 199 / 31,128 | 199 / 27,805 | 199 / 44,925 | 199 / 37,587 |

Stock check (runs 1 and 2): both products went from 1,000,000 to 999,580 = 420 units each (20 warm-up + 400), so no lost
update and no deadlock-induced failure under mixed item order.

Reading:
- Every order but the first waits for the hot-row lock (199 waits per 200 orders): order creation is serialised on
  `P00001`. Throughput stays ~38-45 orders/s regardless of client concurrency, so the hot row, not the pool, is the
  ceiling at this load.
- Derived (not directly measured) average hot-row lock hold: wall time / orders = 4.5-5.3 s / 200 ≈ **22-26 ms per
  order**. MySQL exposes wait time, not hold time; the read-write transaction average (85-129 ms) is larger because it
  includes the time each transaction spent waiting for the lock before holding it.
- At c=8 (< pool size) Hikari wait is ~0; at c=32 the pool saturates (10 active, ~21-22 pending) and the mean wait for
  a connection (216-261 ms) becomes the largest latency component, i.e. requests queue on the pool while the
  connections inside it queue on the row lock. Raising the pool would only move the queue into InnoDB lock waits.
- Each order used 2 connection checkouts and 2 read-write transactions; I did not attribute the second one (likely the
  idempotency/request path) — worth a look if pool pressure matters.

## (3) Independent critical review of `PublicRecommendationCache`

Derived from 071's contract and the code, then tested. Verdict: correct for its contract; no production change made.

| Property | Finding | Evidence |
|---|---|---|
| Single-flight | Correct: one `Entry` per key via `computeIfAbsent`, load under `synchronized(entry)` with a re-check. | existing `concurrentMissesLoadOnce`; app-level burst above (2 aggregates for 32 callers). |
| Failure not cached | Correct for a cold key (entry removed) and for a refresh after expiry (old expired value kept but `fresh()` is false, so it is never served and the next call retries). | existing `failuresAreNeverCached`; **new** `failedRefreshAfterExpiryIsNotServedStaleAndIsRetried`. |
| Map cap 2000 | Correct: full map of fresh entries → new keys are served uncached; after expiry the next new key evicts expired entries and is cached. Not strictly atomic (size check then `computeIfAbsent`), so concurrent first-time keys can overshoot by at most the number of concurrent requests; bounded, acceptable. Was untested. | **new** `mapIsCappedAndOverflowFallsThroughUncachedUntilExpiredEntriesCanBeEvicted`. |
| TTL 0 disables | Correct; a negative configured value is clamped to 0 (`Math.max`), and would also disable without the clamp because the check is `ttlNanos <= 0` — the clamp is redundant but harmless. | existing `zeroTtlDisablesCaching`; **new** `negativeConfiguredTtlDisablesCachingLikeZero` (via the Spring constructor). |
| Thread-safety / publication | `value` and `expiresAtNanos` are `volatile`; the lock-free fast path can momentarily see a new `value` with the old expiry and fall into the lock, which re-checks — safe. `nanoTime` overflow handled by subtraction compare. | code review. |
| Memory | Bounded: ≤ 2000 entries × ≤ 20 items; invalid `limit` and unknown products never create an entry. The cached list is `List.copyOf` (immutable) so callers cannot corrupt a shared result. The `Product` objects inside are shared mutable instances; only Jackson reads them today. Expired entries linger until the cap is hit (bounded by the cap). | **new** `cachedListIsAnImmutableSnapshotSharedSafelyBetweenCallers`. |
| Low-severity gap (not fixed) | If the loader fails while other callers wait on the same entry, the entry is removed; a waiter that then loads successfully stores its result on the orphaned entry, so the next caller misses once more (one extra DB load, no wrong data). Also, waiters on a failing key each retry the load one after another (serialised, not parallel). | code review only; flagged as follow-up, no test asserts the suboptimal behaviour. |

Mutation check (each mutation applied to `PublicRecommendationCache.java`, `PublicRecommendationCacheTest` run, file
restored; restored copy byte-identical, then 10/10 green):

| Mutation | Result |
|---|---|
| M1 delete the cap fall-through (`if (entries.size() >= MAX_ENTRIES) return service.forProduct(...)`) | **red** 1/10: `mapIsCapped…` (new test) |
| M2 drop `List.copyOf` | **red** 1/10: `cachedListIsAnImmutableSnapshot…` (new test) |
| M3 `fresh()` ignores expiry | **red** 3/10: `failedRefreshAfterExpiry…` (new), `entryExpiresAfterTtl…`, `mapIsCapped…` (new) |
| M4 remove `synchronized (entry)` | **red** 1/10: `concurrentMissesLoadOnce` |
| M5 cache the failure (empty list) instead of removing the entry | **red** 1/10: `failuresAreNeverCached` |

## Verification (commands actually run)

- `mvn -q test -Dtest=PublicRecommendationCacheTest` → 10/10, 0 failures/errors/skips.
- Mutation script (M1-M5) → each red as tabled; final restored run 10/10.
- `mvn -q test -Dtest=PublicRecommendationCacheTest,RecommendationServiceTest,RecommendationIntegrationTest,OpenApiDocsTest`
  → exit 0: 10 + 7 + 9 + 5 = **31/31**, 0 failures/errors/skips (JaCoCo skipped for the targeted run).
- `bench/sqlperf/app_load.py` → two complete runs (run1, run2), exit 0. Two earlier attempts failed and were fixed in the
  script: actuator behind the JWT filter on the API port (→ loopback management port) and missing `V2__member_admin.sql`
  (→ apply every migration).
- Full `mvn clean test` was **not** run (test-only + bench change; production code untouched).

## Not covered

- Rate limiting for the public endpoint (still open from 071; needs Caddy-level limiting or trusted forwarded headers).
- Production-like numbers: one host, Docker Desktop shared CPU, client and server on the same machine; synthetic data.
- Pool sizes other than the default 10, and contention patterns other than one hot product (e.g. many cold products,
  coupon-row contention).
- Hot-row lock **hold** time is derived from throughput, not measured directly.
- `bench/sqlperf/capture.py` still applies only `V1__baseline_schema.sql` (fine for its query shapes today, but it will
  drift as migrations are added) — flagged, not changed.
- Attribution of the second connection/transaction per order and of the residual statements during cache hits.
- No independent review of this closeout by the other agent.

## Next step

1. Owner decision on rate limiting for `GET /api/products/{id}/recommendations` (Caddy plugin vs trusted proxy headers).
2. Optional: fix the orphaned-entry case (re-insert or `putIfAbsent` the loaded entry) with a deterministic test.
3. Optional: if checkout throughput on a single hot product matters, evaluate the Redis stock path (`stock.redis.enabled`)
   with `app_load.py`, since the measured ceiling is the hot row (~40 orders/s here), not the pool.

## Engineering concept

A cache in front of an expensive read only pays off for repeated keys; single-flight additionally collapses a
thundering herd at expiry into one load, which is what turned 64 aggregates into 2 for 32 simultaneous callers. Under
concurrent writes the bottleneck is the most contended row lock: once clients exceed the pool size the queue simply
moves from InnoDB lock waits into Hikari's pending queue, so enlarging the pool does not raise throughput.
