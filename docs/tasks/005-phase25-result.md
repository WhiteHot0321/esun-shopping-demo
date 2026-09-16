# Task 005 — Phase 2.5 completion result

- 日期：2026-09-15（Asia/Taipei）
- 分支：`advanced-v2`
- 狀態：A／C／B 已有本地實作與測試紀錄，待獨立審查與完整驗收；未 commit、push 或 merge。先前「全部完成」判定更正如下。

## Changes

- Prompt A existing idempotency flow retained and revalidated.
- Prompt C moved the transaction implementation into `OrderTransactionService`; `OrderService` now applies Spring Retry outside the transaction with capped jittered backoff and `CONCURRENT_CONFLICT` handling.
- Prompt B added Redis 7, Lua all-or-nothing stock decrement, preload, compensation, audit, feature flag, and DB-only fallback.
- k6 scripts now register a benchmark member and send JWT credentials after Phase 2 auth became active.

## Verification

- Backend Maven: 59 tests, 0 failures/errors/skipped.
- Redis integration: 2 tests passed with real Redis Testcontainers.
- Retry integration: 2 tests passed; retry-to-success and exhausted-retry cases.
- Frontend checkout: 3 passed; Vite build passed.
- Redis-enabled HTTP run: 20 VUs / 10 seconds, 715 successful orders, 0 non-success responses.
- Post-run stock audit: MySQL P002/P003 = 285/285; Redis P002/P003 = 285/285.
- k6 `inspect` and `git diff --check` passed.

## Notes

### C/B acceptance repair round 1 — 2026-09-15

- RedisUnavailableOrderIntegrationTest: 1/1 passed against real MySQL with Redis TCP connection refused; verified DB rollback, successful retry/replay, and no compensation without a confirmed reservation. Maven exit 1 only at the restored coverage gate for this narrow selection.
- RealDeadlockRetryIntegrationTest: deterministic 20 independent InnoDB cycles. With maxAttempts=3, 20/20 succeeded and 20 actual retries; with maxAttempts=1, 0/20 succeeded, all failures asserted MySQL vendor code 1213, claims rolled back and stock unchanged. Both JUnit runs passed; narrow Maven runs still exit 1 at coverage gate. Spy coordinates lock timing only, never throws a fake deadlock. Initial fixture range-lock timeout was resolved by using primary-key point updates for contender weight. This is a controlled failure test, not a claim that ordinary k6 produces the same failure rate.
- Added explicit Phase25K6Acceptance runner (not part of default suite): uses disposable MySQL/Redis, real HTTP/JWT and existing 3-item k6 script at 20 VUs/20 seconds, verifies order count, exact DB stock and Redis audit. First Redis-enabled run in progress.

- Independent Claude static review completed: `.git/codex-claude-runs/edffbbb5-7610-4187-a05e-7316039723cd/result.json`. Four confirmed blockers: replay after sellout, duplicate-item Lua underflow, incomplete/unreachable audit, removed coverage gate. Reviewer did not execute tests. Its blanket statement that concurrent duplicates are safe is not accepted for the last-unit race; new integration coverage targets that case.
- Codex repair: DB idempotency claim now precedes the Redis reservation (Redis still precedes DB inventory writes); rollback compensation stays outside the transaction. This intentionally refines Prompt B's transaction ordering to preserve Prompt A under concurrency. Duplicate claims never touch Redis.
- Lua aggregates repeated keys before checking/decrementing. Reservation result distinguishes RESERVED/INSUFFICIENT/BYPASSED; compensation only applies to confirmed reservations and executes atomically.
- Missing keys/Redis errors latch this process into DB-only mode, rather than blindly reusing possibly stale values after reconnection. Ambiguous network outcomes can still cause cache drift; audit and maintenance reconciliation are required before re-enabling. This is not a distributed transaction or a claim of automatic cross-instance recovery.
- Preload reads all DB products including zero stock and uses SET NX to avoid overwriting live reservations. Scheduled audit reports drift and degraded state; checks during active writes may observe in-flight reservations.
- C adds separate machine-readable response codes, per-retry counting/logging, and a configurable negative-control attempt count clamped to 1..3. Original coverage gate restored and extended to transaction/cache classes.
- New real Redis + MySQL tests cover twenty same keys competing for one unit, replay after sellout, twenty distinct keys, repeated-item rejection and actual SQL-constraint rollback/compensation. Initial targeted run: 13 passed, 1 fixture error (CREATE TRIGGER requires SUPER with binary logging); replaced the fixture with a product-scoped CHECK constraint, without broadening database privileges. Re-verification pending.
- Still required: Redis outage integration, maxAttempts=1 real deadlock control, k6 comparison and audit, coverage verification, narrow independent repair review, final tests/build, Notion sync, commit and push.
- Narrow rerun: RedisOrderIntegrationTest 4 tests passed, no failures/errors/skips. Maven command exit 1 because restored JaCoCo gate is not satisfied by the narrow run; not reported as overall verification success. Notion four progress pages/table cells updated and read back. Concurrent unrelated docs/project-state.md edits were observed and preserved (not authored by this task).

- 完整驗收仍缺 C 的 `maxAttempts=1` 真實死鎖反向壓測、C／B 獨立審查，以及 Redis 不可用降級與 DB 失敗補償的端到端證據；既有短壓測不能替代這些驗收。
- 2026-09-15 文件同步：直接修改 Notion「階段工作」Phase 2.5 列的狀態與驗收結果，並同步相關進度頁；不得只追加 Log。此輪僅更新文件，不重跑程式測試。
- 本輪耗時、token／cost：unknown；使用者介入：要求同步 Markdown 與表格；程式修復輪次：不適用；獨立審查缺陷數：unknown。

- The local compose benchmark database was replenished to 1000 units for the isolated run; this was runtime test data only and did not change seed files.
- No commit, push, merge, or destructive repository reset was performed.
