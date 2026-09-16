# Phase 2.5 final acceptance — 2026-09-16

## Session gate

- Goal: complete remaining controlled-deadlock HTTP attempts=1/3 comparison, verify final coverage and synchronize Phase 2.5 acceptance.
- Baseline: advanced-v2 ae7363f; existing Task 011/012 results were completed by other sessions since the earlier Docker block. Preserve them. No commit/push/merge by this session.
- Risk/class: heavy validation of inventory/transactions. Implementer of existing repairs: prior Codex work; independent reviewer: Claude Code static PASS (Task 006). No production changes planned; repeat review only for new code/evidence.
- Isolation: detached validation worktree C:/GitHub/esun-shopping-phase25-validation-ae7363f. Tests run sequentially there to avoid shared target/report interference. Raw results outside target: main .git/phase25-final-ae7363f.
- Allowed reads: Phase 2.5 runners, affected services/tests, existing Task 006/007/011/012 evidence, directly related Notion pages. Changes: result/state/bench/handoff documents only unless reproducible blockers demand one of the previously authorized three code/test changes.
- Minimum verification: paired Phase25K6DeadlockAcceptance attempts=1 and 3 with true MySQL 1213, HTTP CONCURRENT_CONFLICT, retry counters and DB stock/order assertions; one final clean full suite with all four service line gates >=80%. Existing 3-round ordinary k6 and outage drill evidence must be checked, not rerun without cause.
- Exclusions: Phase 1.5 historical workloads, other features, optional refactoring, claiming normal-load throughput improvement when no deadlocks occurred.
- Clarification: earlier targeted-recovered.log belongs to 09:04; later shared target reports were replaced by other sessions. Do not combine these into a single run or label the targeted exit 1 as a current full-suite failure.

## Results

Docker engine 28.4.0 is available; prior environment blocker is resolved.

### Controlled HTTP comparison (20 VUs / 20 seconds, real MySQL deadlocks)

| Attempts | Success / total | MySQL 1213 | HTTP concurrent conflicts | Actual retries | Final stock / each item |
|---|---:|---:|---:|---:|---:|
| 1 | 842 / 852 | 10 | 10 | 0 | 9158 |
| 3 | 837 / 837 | 10 | 0 | 10 | 9163 |

Both runner JUnit assertions passed (1 test, 0 failures/errors/skipped per run), including exact DB order and stock deltas. Both Maven commands exited 1 solely because the narrow selection does not cover all gated services. Coverage is decided by the final clean full suite, never by accumulated narrow-run execution data. Raw HTTP JSON/log and per-run JUnit XML preserved in `.git/phase25-final-ae7363f/`.

### Live Redis outage evidence gap and repair

Inspection found Task 012's drill uses a separate broken client and mocked DB truth. It does not itself prove HTTP orders survive pausing a live Redis or that the same degraded instance ignores a restored healthy connection. Added one test file, `RedisLiveOutageIntegrationTest.java`, without changing production code. Its first targeted execution passed:

- Warmup confirms real Redis reservation. Pause only the test-owned disposable container, verify paused state, then complete 20/20 concurrent authenticated HTTP orders while still paused. Exact DB stock=79, new orders/details=21 (including warmup).
- Unpause; same requestId returns identical original response without another deduction. New HTTP order succeeds through DB-only mode; Redis stays unchanged.
- Drain writers; overwrite all app stock keys from real MySQL, including a zero-stock product. Audit becomes empty. A further HTTP order proves the original instance remains degraded even after reconciliation (DB=77, Redis=78).
- Reconcile again; a fresh cache service instance plus the real transactional DB proxy resumes real reservations. Final stock=76, exactly 24 new orders/units, audit empty. This simulates process-local state reset, not a literal JVM restart.
- Narrow JUnit: 1 passed, 0 failures/errors/skipped; Maven exit 1 solely for coverage gate. A later resource-close-only edit is included in the final suite.
- Independent narrow review: Claude Code PASS, raw run b4862015-167c-4f30-b4a9-602da371b340; report `013-phase25-live-outage-review.md`. No new correctness blockers. Original production repair review remains applicable (production unchanged).

### Ordinary matrix / final full suite

Earlier 18-round summaries remain historical; their raw outputs are absent from the current target directory after prior clean builds. This independently executed matrix uses the same fixed snapshot, with sequential runs and every output preserved outside target. Historical overlapping batches are not combined with these measurements.

| Configuration | Round 1 | Round 2 | Round 3 | Success median / range | Request count median / range |
|---|---:|---:|---:|---|---|
| B0 (Redis off, attempts=1) | 831/831 | 843/843 | 841/841 | 100% / 100–100% | 841 / 831–843 |
| C3 (Redis off, attempts=3) | 835/835 | 838/838 | 831/831 | 100% / 100–100% | 835 / 831–838 |
| R3 (Redis on, attempts=3) | 820/820 | 836/836 | 836/836 | 100% / 100–100% | 836 / 820–836 |

Each run: 20 VUs/20 seconds, fresh disposable MySQL/Redis, 10,000 units per product, zero retries, exact new-order delta and each product's final stock=10000-success. R3 audit empty in all three runs. Each JUnit runner passed 1/1, 0 failures/errors/skips. Maven exit=1 on all nine narrow selections because unrelated gated service coverage remains incomplete; this is recorded explicitly, not represented as a successful Maven build. All six non-200/no-response counter buckets were zero (missing k6 counters imply no samples). Raw files: `{B0,C3,R3}-r{1,2,3}.{json,xml,log}` and `*-k6.log`, plus `matrix-exits.jsonl` and `matrix-summary.json` in the evidence directory.

No current ordinary-load run reproduced historical ~50% failure; no throughput improvement from Redis or retry is claimed. Fault tolerance is demonstrated separately by controlled deadlocks and live Redis pause. High-load sold-out latency studies remain optional; last-unit replay and no-negative-stock correctness remain required and covered by the integration suite.

### Final clean verification — 2026-09-16 13:44 Asia/Taipei

- `mvn -Dtest=RealDeadlockRetryIntegrationTest -Dorder.retry.max-attempts=1 test`: JUnit 1/1 passed; 20 real MySQL 1213 victims, 0 successes/retries; claims rolled back, both stocks unchanged. Maven exit 1 only for narrow coverage (raw log/XML retained).
- `mvn clean test`: **exit 0; 76 tests, 0 failures/errors/skipped**, including the new live outage test and all existing idempotency, rollback, concurrency, error semantics, cache compensation and outage tests. Real-deadlock default attempts=3: 20/20 succeeded, exactly 20 retries and expected stock. Explicit k6 runners are verified separately above, not counted in these 76.

| Gated class | Covered / total lines | Coverage | Required |
|---|---:|---:|---:|
| OrderService | 34/36 | 94.44% | >=80% |
| OrderTransactionService | 52/52 | 100% | >=80% |
| StockCacheService | 60/63 | 95.24% | >=80% |
| ProductService | 18/18 | 100% | >=80% |

Fresh report contains `All coverage checks have been met` and `BUILD SUCCESS`. Narrow-run coverage was discarded by `clean`; gate settings/exclusions were not relaxed. `final-clean-test.log`, `final-surefire/`, `final-jacoco.csv/xml`, and `final-exit.txt` preserve this run outside target.

## Completion audit

| Requirement | Authoritative evidence | Result |
|---|---|---|
| A: one order per requestId, replay after sellout, distinct keys, rollback | final-suite RedisOrderIntegrationTest, OrderIdempotencyIntegrationTest, OrderRollbackIntegrationTest and new live-outage HTTP replay | Pass |
| C: real lock failures, capped retry outside transaction, 409 CONCURRENT_CONFLICT vs DB_ERROR | current real-deadlock 20-caller 1/3 pairing; controlled HTTP 1/3 pairing; final OrderRetryTest and OrderControllerTest; existing independent source review | Pass |
| B: atomic multi-item/repeated-item decrement, confirmed-only compensation, zero-stock audit, safe preload | final RedisOrderIntegrationTest, StockCacheServiceIntegrationTest, RedisUnavailableOrderIntegrationTest | Pass |
| B: actual live outage with real orders, DB authority, recovery, latch persistence | new RedisLiveOutageIntegrationTest: 20/20 authenticated HTTP orders while test Redis paused, exact stock/order/detail counts, replay, recovered audit | Pass |
| Ordinary B0/C3/R3 >=95%, 3 rounds/config, exact data reconciliation | 9 preserved JSON/XML/log sets; 7,511/7,511 total orders; all R3 audits empty | Pass |
| Four service gates >=80%, clean full backend regression | fresh 76/76, Maven exit 0 and JaCoCo counts above | Pass |
| Independent correction review | original Task 006 static PASS plus narrow new-test review PASS (Task 013 report) | Pass |
| Artifact integrity | source manifest 79 entries matches main checkout; new test SHA256 BDF1A0BD4F6C3EDFA454F36B3274A6932F1B59EB35D3898CEBA38534D804A5EE matches validated worktree | Pass |

## Delivery and limits

- Phase 2.5's requested fault/reverse-load/review/coverage acceptance is complete on ae7363f plus this uncommitted test. No production code changed; no commit/push/merge by Codex in this session. Earlier commits by other sessions remain intact.
- New test is the only code/test file changed. Existing generation/runtime artifacts retained in the isolated validation worktree and `.git/phase25-final-ae7363f/`; tracked result documents retain the acceptance ledger.
- Ordinary-load historical ~50% improvement is not reproduced or claimed. The nine normal runs had no non-200/no-response counters and zero retry events; scanned normal logs had no matches for SQL 1213/1205/45000/1644 error markers. Controlled tests prove retry effectiveness separately.
- Recovery still requires all writers paused/drained and DB-authoritative reconciliation; cache is not a distributed transaction. Fresh-instance reset is tested, not an OS/JVM restart. Broader sustained sold-out performance studies, multi-instance recovery and Phase 1.5's separate historical workload remain out of scope; final-unit/replay correctness is tested.
- Engineering concept: coverage configuration, measured clean-suite coverage, static review and real dependency-failure behavior are separate evidence layers. A green narrow JUnit selection can coexist with a red whole-service coverage gate; preserve both outcomes and require the final clean gate to pass.
- Metrics: resumed validation started approximately 13:27; final suite ended 13:44, documentation/sync continued afterward. One test authored; no functional repair rounds, one resource-close-only edit; one valid evidence gap corrected. New narrow review: 193.242 seconds, 14 turns, tool-reported USD 0.2995648 (not a billing statement); coordinator context/token/cost and five-hour delta unknown. No additional user approval requested; user asked to confirm progress and continue. Prior Docker recovery was performed by user.
- Local/Notion synchronization and final diff check recorded at closeout below.

## Closeout — 2026-09-16 13:50 Asia/Taipei

- Directly updated and fetched back all five required Notion pages: [進度追蹤](https://app.notion.com/p/3c4708da9f9280d89606c93c3a6d3e53), [提示詞整理／階段工作](https://app.notion.com/p/3c2708da9f9280b083d3f000ff381579), [執行順序與策略](https://app.notion.com/p/3d7708da9f928173be92dfc94182db9f), [Phase 2.5](https://app.notion.com/p/3d8708da9f9281bda389d0599e1499e7), [執行計劃](https://app.notion.com/p/3d4708da9f9281dc8b3ddb0423cbc0ad). Stage and strategy table status/result cells and Phase checklists were changed directly, not just a appended log. All required Phase 2.5 checklist items checked with explicit evidence/limits; no unsynchronized pages remain.
- Local Task 007 P25 result rows, Task 006 closure pointer, bench guide, project state and handoff now refer to this final evidence. Historical other-session results remain preserved.
- `git diff --check` exit 0; separate check of the three new untracked files found zero trailing-whitespace violations. Final Surefire XML sum independently matches 76 tests / 0 failures/errors/skips. Runtime source hashes remain identical to the validated snapshot; Markdown documentation was subsequently updated and is not runtime code. HEAD remains ae7363f.
- Total resumed validation and closeout: approximately 23 minutes (13:27–13:50), no application repair rounds or additional scope-approval questions; one new meaningful fault test, targeted verification, one narrow independent review, nine normal k6 runs, paired HTTP deadlock runs, real negative control and one final clean suite. Coordinator token/cost remains unknown.
- Completion: requested Phase 2.5 acceptance achieved. Changes remain uncommitted. Next action is optional user review/explicit commit request, not another autonomous feature or regression run.
