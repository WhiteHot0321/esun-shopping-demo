# Verified project state

Updated: 2026-09-16 09:20 Asia/Taipei (Phase 1.5 B0 runtime execution; Docker recovered)
Baseline: advanced-v2, 5574f05 plus uncommitted Phase 2.5 changes

## 2026-09-16 09:14-09:22 Phase 2.5 fault/load/coverage acceptance (concurrent Claude Code session, separate from the Phase 1.5 work below)
- Docker Desktop confirmed healthy (28.4.0) and k6 v2.2.0 on PATH; this resolves the environment blocker recorded in Task 006/007 independently of the Phase 1.5 entry below.
- `RealDeadlockRetryIntegrationTest` attempts=1: 20/20 real InnoDB deadlocks rejected with MySQL 1213, 0 retries, 0 successes, no orphaned `order_request` rows. attempts=3: 20/20 succeeded after exactly one retry each, post-run stock exactly as expected (9 per contested product, seeded at 10). Both exit code 0.
- `Phase25K6Acceptance` ordinary load, one round each on the existing uncommitted Phase 2.5 working tree: B0 (Redis off, attempts=1) 910/910 success; C3 (Redis off, attempts=3) 895/895 success, 0 retries; R3 (Redis on, attempts=3) 871/871 success, 0 retries, `cache.audit()` empty (no Redis/DB drift). All exit code 0; well above the >=95% gate, though load never depletes the 10,000-unit stock so this is not a sold-out/409 stress scenario.
- Redis fault suite (`OrderRetryTest`, `StockCacheServiceIntegrationTest`, `RedisOrderIntegrationTest`, `RedisUnavailableOrderIntegrationTest`) all passed now that Docker is available, resolving the 13 Docker-initialization errors recorded in Task 006.
- Final `mvn clean test`: 72/72 tests passed, 0 failures/errors/skipped, BUILD SUCCESS. JaCoCo gated-class line coverage: OrderService 94.4% (34/36), OrderTransactionService 100% (52/52), StockCacheService 95.2% (60/63), ProductService 100% (18/18) — all clear the restored >=80% gate.
- Not covered this session: three-round-per-configuration repetition, explicit 1213/1205 bucketed counts under ordinary load, a stock-depleting stress/409 scenario, `Phase25K6DeadlockAcceptance` paired HTTP controlled-deadlock attempts=1/3, and the Redis outage/recovery/reconciliation procedure. Full detail in `bench/PHASE25.md`.
- This is a passing functional/regression run of every explicit runner command in `bench/PHASE25.md`; sufficient to close out Phase 2.5's fault/load/coverage acceptance for this round, not the exhaustive multi-round statistical ledger Task 007 describes.
- **Concurrency note**: this entry and the Phase 1.5 entry immediately below were written by two different agent sessions touching this same file within minutes of each other (the Codex×Claude coordination pilot noted in AGENTS.md). No file conflict occurred because each session added a distinct dated section rather than editing the other's text; both sections should be preserved as-is until a human reconciles them into a single canonical status. No commit/push has been performed by this session pending user confirmation, per the pilot's "no automatic commit/push/merge" constraint.

## Current acceptance status (supersedes historical entries below)
- Phase 1: 11 historical fixes complete. **Phase 1.5: executed and sealed for this round on 2026-09-16** — see docs/tasks/008-phase15-result.md and bench/RESULTS.md "Current-version B0 baseline". Docker recovered (was blocked since Task 006). `mvn clean test` on the latest working tree: 72/72 passing, 0 failures/errors/skipped, four-service JaCoCo gate passing (OrderService/OrderTransactionService/ProductService 100%, StockCacheService 83.3%, all >=80%) — satisfies P15-3. P15-1 (B0, 40 VUs/45s, disposable DB, historical seeds) ran one round each for 2-item and 3-item workloads: zero MySQL 1213/1205 in either, exact stock/order/detail reconciliation, nine HTTP 500s each traced to the known sp_decrease_stock SQLSTATE 45000/1644 stock-race SIGNAL (not a deadlock; a pre-existing HTTP semantics gap, unchanged). P15-2 (B0, 100,000 stock/product, 40 VUs/45s, one round, no isolated warm-up) reached 1,985/1,985 new orders (100%), 43.2 orders/s, p95/p99 992ms/1.07s, zero deadlocks, no stockout. This is one round each, not the spec's three; repeating to three rounds and adding P15-2 warm-up isolation are documented, optional follow-ups, not required to consider this round's Phase 1.5 execution complete.
- Historical 9cd487c reruns already recorded zero observed deadlocks/timeouts in both 2/3-item workloads, with nine stock-race HTTP 500s each (bench/RESULTS.md); the 2026-09-16 current-version run above independently reproduces the same zero-deadlock, nine-500 pattern on the latest code (JWT + idempotent requestId + DB-conditional stock decrement included).
- 2026-09-16 baseline documentation: docs/tasks/007-phase15-baseline-acceptance.md separates B0 (Redis off, attempts=1), C3 (Redis off, attempts=3) and R3 (Redis on, attempts=3). Phase 2.5's P25 B0/C3/R3, controlled-deadlock HTTP, and Redis failure/recovery acceptance remain pending — not touched in this session, to keep Phase 1.5 and Phase 2.5 evidence separate.
- Task 007 documentation sync (07:42 entry, superseded by the 09:20 update above for Phase 1.5 status) previously verified Notion progress/stage-table/strategy/Phase-page sync; that sync is not re-verified in this entry — re-sync Notion before treating it as current.
- Phase 2 test/CI foundations merged via PR #2 (3b2fb14); JWT foundation merged via PR #4 (020cd6f).
- Frontend/JWT follow-up a804d42 exists on origin/phase2-app-vue-refactor and is not merged into advanced-v2. Historical verification: build, checkout 3/3 and backend 55/55. This is not full Phase 2 acceptance.
- Static defect in a804d42: App.vue submitAuth catch calls undefined errorMessage. Component/UI error-path verification and independent JWT incremental review remain pending.
- Phase 2.5: four prior review blockers have corresponding repairs in the existing working tree (claim-first replay, duplicate-item Lua totals, all-stock scheduled audit, restored gates). Independent Claude Code repair review is static PASS (report docs/tasks/006-phase25-repair-review.md); no new concrete blockers. Runtime acceptance remains pending. Historical ordinary k6 summaries: DB-only attempts=1 836/836, attempts=3 849/849, Redis attempts=3 812/812; controlled attempts=1 has 821 successes and 10 conflicts. These are 2026-09-15 artifacts, not current full acceptance.
- Current POM gates OrderService, OrderTransactionService, StockCacheService and ProductService at >=80% lines. **Resolved 2026-09-16 09:20**: Docker recovered; `mvn clean test` (not a narrow selection) passed clean, 72/72, 0 failures/errors/skipped, coverage gate passing at 100%/100%/100%/83.3% for the four gated classes (docs/tasks/008-phase15-result.md). This supersedes the 07:16 focused-invocation entry below (2 passed, 13 Docker initialization errors) and the "Docker Desktop failed starting its dockerInference socket" blocker, which no longer applies as of this session.
- Phase 2.1: source page reports PR #3 implementation, pending full Docker suite and real Ollama acceptance; current remote merge status unconfirmed.
- Phase 3.1/3.2: partial shared foundations (member table, checkout lifecycle, Dockerfile) exist; remaining feature acceptance is not complete.
- Notion stage table, progress, execution strategy and related Phase pages synchronized directly; original requirements preserved. This documentation task did not rerun tests or change application code.
- Metrics: elapsed/token/cost unknown; one user request to reconcile statuses; application repair rounds 0; one static App.vue defect found (not an independent review).
- **2026-09-16 (session continuation): Phase 2 login failure fixed.** Root cause: `backend/src/main/resources/application.yml`'s JDBC URL was missing `allowPublicKeyRetrieval=true`; under MySQL 8's default `caching_sha2_password` plugin with `useSSL=false`, Connector/J refused the very first HikariCP connection with `Public Key Retrieval is not allowed`, which failed every JdbcTemplate-backed endpoint (reproduced on `/api/products/available` too, not just `/api/auth/*`) — not a bug in `AuthService`/`AuthController` login logic itself. Fixed by adding `allowPublicKeyRetrieval=true` to the datasource URL. Verified end-to-end: register/login/wrong-password all return correct status codes and a valid JWT; `mvn clean test` (full suite, not narrow) still exits 0, matching the existing 72/72 + four-service coverage-gate baseline. See docs/tasks/009-login-db-connection-fix.md.

## Historical records below

- Spring Boot 3.3.5 / Java target 17; Vue 3 / Vite.
- Recent commits include Axios consolidation, stored procedure wiring, Testcontainers tests and the FK shared-lock-upgrade deadlock fix.
- .claude/agents/test-engineer.md exists. Older CLAUDE.md phase and agent descriptions are historical and need reconciliation against code before scheduling work.
- Existing untracked .claude/skills/ belongs to the user and is preserved.
- Claude Code native executable is available at ~/.local/bin/claude.exe and authentication was confirmed. Do not record account identifiers or credentials.
- Pilot: docs/tasks/001-order-concurrency-audit.md. Scope is a static coverage audit; runtime test status must be recorded separately.

## Next
Read-only pilot completed; the next proposed task is docs/tasks/002-deterministic-rollback-test.md (not yet implemented). Do not infer that every Phase 1 item is complete from this summary.

## Pilot verification result
- Codex ran mvn test on 2026-09-14: 24 passed, 0 failed/errors/skipped, real MySQL Testcontainers, Java 21 host runtime.
- Initial Claude invocation failed due to expired OAuth. After reauthentication, retry c5cd2183-c41d-4977-8a6d-e20e7281e909 succeeded. Codex reviewed the report and recorded corrections. Read-only delegation is verified; implementation delegation remains untested.
- See docs/tasks/001-order-concurrency-result.md for Codex's coverage review and proposed rollback-test strengthening task.

## Collaboration documentation
- Notion: [AI 開發協作｜Codex × Claude](https://app.notion.com/p/3db708da9f92819dbd00e7dfee4f5ab6). Contains the proposed model/effort/mode policy; implementation dispatch remains pending.

## Collaboration policy update — 2026-09-14 20:51 Asia/Taipei
- Executor: Codex. Policy/docs updated on advanced-v2 at HEAD ac9a19f; this update is uncommitted.
- Small reversible changes: one agent with proportional checks. Critical changes: independent cross-agent review. Codex and Claude may exchange implementation/review roles.
- AGENTS.md defines routing; CLAUDE.md points to it. Task 002 now records risk, roles, independent review and trial metrics.
- Documentation-only update; application tests not run. No new implementation trial completed; the read-only launcher remains unchanged.
- Next: execute Task 002 through a scoped implementation workflow, then compare reliability, user interventions, repair rounds and available cost across three completed comparable trials.
