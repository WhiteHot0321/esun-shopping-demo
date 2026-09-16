# Phase 2.5 C/B independent acceptance review

> Runtime closure: [Task 013](013-phase25-final-acceptance.md), 2026-09-16 13:44, supersedes the Docker-blocked and pending-runtime status in this historical record. Final 76/76 and four-service gate pass, paired HTTP deadlocks, nine normal k6 runs and live Redis pause verified. Original static review remains valid; a narrow independent review covers the new test.

## Resumed acceptance gate — 2026-09-16 Asia/Taipei

- Goal: resolve Phase 2.5 coverage and remaining failure/load/review acceptance.
- Class: Heavy, bounded to existing Phase 2.5 work; user explicitly approved expanded reads, required fault/k6 comparisons, one independent review and one final backend suite in this session.
- Baseline: advanced-v2 @ 5574f058583c28869656f6ae77eab848795efa4e plus pre-existing uncommitted changes; preserve them.
- Allowed reads: Phase 2.5 services, related repositories/DTO/exception/Lua/configuration, affected tests and bench runners, task records and directly relevant Notion pages.
- Change limit: three code/test files plus necessary progress documents. No other Phase, refactor, commit, push or merge.
- Implementer/verification: Codex. Independent repair reviewer: Claude Code, read-only via scripts/invoke-claude.ps1; review after targeted evidence, before final full verification.
- Acceptance: >=80% line gates on OrderService, OrderTransactionService, StockCacheService and ProductService; real Redis/MySQL replay/rollback/outage assertions; 20-worker real-deadlock attempts=1 vs 3; ordinary k6 same-condition comparisons and controlled-deadlock HTTP comparison; exact final stock/order counts; independent repair review; final clean backend suite.
- First targeted attempt: 15 tests, 0 failures, 13 initialization errors, 2 passed; Docker engine absent. Environment recovery in progress, not an application repair.
- Metrics so far: one scope-approval user intervention; no code changes; model/reasoning and context/usage/cost metrics unavailable. Start approximately 07:07 Asia/Taipei.

## Resumed result — 2026-09-16 07:16 Asia/Taipei

- Independent Claude repair review: static PASS, no new concrete blockers. Full report: `docs/tasks/006-phase25-repair-review.md`; raw run `359a0f34-6c91-4db8-a754-9668384827ee`. Reviewer did not execute tests. Scheduled audit satisfies the original Notion requirement (scheduler/admin endpoint/CLI were alternatives); no endpoint is required.
- Executed: focused Maven command above, exit 1, 15 tests / 0 failures / 13 Docker initialization errors / 0 skipped. The two OrderRetryTest tests passed. `git diff --check` passed (line-ending warnings only).
- Not executed after recovery: real-deadlock 1/3 controls, ordinary and controlled k6 comparisons, final clean full backend suite. Coverage gate exists but passing ratios remain unproven.
- Environment: Docker Desktop startup aborts because `%LOCALAPPDATA%/Docker/run/dockerInference` cannot be accessed/removed. Automatic process-stop/socket-cleanup command was rejected by policy; no cleanup executed. User asked to restore engine manually; no factory reset, data reset or volume deletion performed.
- Historical artifacts inspected: ordinary k6 DB-only attempts=1 836/836; attempts=3 849/849; Redis attempts=3 812/812. Controlled attempts=1 821 successes / 10 conflicts. They are dated 2026-09-15 and do not establish current full acceptance or a throughput improvement over historical ~50%.
- Current turn code/test edits: zero; existing uncommitted repairs preserved. Application repair rounds: zero. One environment startup attempt failed; no duplicate test rerun while Docker unavailable. No commit/push/merge.
- Engineering concept: restoring a coverage rule is configuration evidence; a fresh clean full-suite report is execution evidence. A static PASS establishes review completion, not fault tolerance or load acceptance.
- Next: restore Docker engine, rerun the same focused tests, then the explicit benchmark matrix in bench/PHASE25.md plus Phase25K6DeadlockAcceptance attempts=1/3; run one final `mvn clean test` and sync measured results. Do not repeat the completed static review absent code changes/new evidence.

- Baseline: advanced-v2, 5574f058583c28869656f6ae77eab848795efa4e plus existing uncommitted Phase 2.5 changes.
- Scope: complete C deadlock retry and B Redis stock cache without regressing A idempotency.
- Risk: critical inventory, transaction, concurrency and API semantics.
- Implementer: Codex. Independent reviewer: Claude Code (read-only).
- User authorizes commit and push only after review and full acceptance pass. Preserve main and unrelated changes.

## Reviewer instructions

Derive expected behavior below, then read current source and tests before any author result report. Do not read docs/tasks/005-phase25-result.md until you have reached your independent findings. Do not edit files or spawn workers. Report concrete blockers with file/line references and reproduction scenarios; distinguish static review from executed tests (you cannot execute tests).

Requirements: A same requestId must return original order even after inventory sells out; concurrent same key creates one order and deducts once; all DB writes and claim roll back together. C uses separate retry/transaction beans, caps at 3 with jitter, retries real lock failures after rollback, reports 409 CONCURRENT_CONFLICT vs 500 DB_ERROR, logs requestId on exhaustion and counts retries. Acceptance requires 20-worker baseline comparison and maxAttempts=1 negative control, not mocks alone. B atomically checks/decrements all items, never partially decrements or goes negative (including repeated product IDs), preserves DB conditional backstop, compensates only actual reservations on rollback/replay, falls back when Redis is unavailable, safely preloads and offers an accessible stock audit including zero/unavailable products. Feature-off behavior must remain unchanged. Required verification includes real Redis/MySQL integration, failure compensation, unavailable Redis and replay behavior, and 20-worker >=95% success with no drift. Inspect coverage gates as well as tests; do not accept removal of existing gates as passing verification.

Read backend/src/main/java/com/esun/shop/service/{OrderService,OrderTransactionService,StockCacheService}.java, related repositories/DTOs, exception handler, redis Lua, backend/pom.xml, relevant service/integration tests, bench scripts and configuration. Keep review bounded to these requirements. Return a prioritized blocker list and missing acceptance evidence.

## Execution evidence — 2026-09-15 Asia/Taipei

- Codex reran `mvn -q "-Dtest=OrderRetryTest,StockCacheServiceIntegrationTest" test`: exit 0; Surefire reports 2 + 2 tests, zero failures/errors/skips. This is baseline targeted verification, not full acceptance.
- Claude read-only review started via `scripts/invoke-claude.ps1`; unified exec session 56130 remains live at last observation. Poll this handle before considering a restart. No report yet.
- MySQL esun-mysql (3308) and Redis esun-redis (6381, healthy) are running; no data reset performed this turn.
- Notion required reads succeeded on retry. Existing progress remains pending independent review/full acceptance; no completion or push claimed.
- Current POM diff removes OrderService coverage gate; must restore equivalent coverage for the split transaction implementation before final acceptance.
- No implementation repairs, commit or push yet. Metrics: review in progress, elapsed/cost unknown; user intervention authorizes commit/push after success.

- Review metrics (tool-reported, not an invoice): 253.162 seconds, 35 turns, USD 0.665881; 44 input / 71,024 cache creation / 876,365 cache-read / 20,517 output tokens. Coordinator token/cost and five-hour usage change unknown. Resumed elapsed approximately 10 minutes; one scope approval and one Docker recovery request; Notion five affected pages updated and read back, Phase 2.5 table status/result and checklist agree with static PASS + runtime blocked.
